# 로드맵 1.13 — 재고 락 4종(NONE/PESSIMISTIC/OPTIMISTIC/DISTRIBUTED) 성능 비교.
# 부록 C: "벤치마크 숫자가 노이즈" → 이 스크립트는 반드시 CI가 아닌 로컬 환경에서 돌릴 것.
# benchmark-lock-strategies.sh의 PowerShell 이식.
#
# 사용법 (PowerShell):
#   docker compose -f docker-compose.yml up -d
#   Start-Process powershell -ArgumentList '-NoExit','-Command','.\gradlew.bat mockPgRun'
#   .\scripts\benchmark-lock-strategies.ps1
#
# 실행 후 benchmarks/raw/<전략>.json의 k6 summary를 읽어
# benchmarks/01-lock-strategies.md 표를 채운다 (워밍업 후 3회 반복, 중앙값 — CLAUDE.md).

$ErrorActionPreference = "Stop"
. "$PSScriptRoot/_wait-for-app.ps1"

$Strategies = @("NONE", "PESSIMISTIC", "OPTIMISTIC", "DISTRIBUTED")
$ProductId = if ($env:PRODUCT_ID) { $env:PRODUCT_ID } else { 1 }
$Stock = if ($env:STOCK) { $env:STOCK } else { 100 }
$Vus = if ($env:VUS) { $env:VUS } else { 50 }
$Duration = if ($env:DURATION) { $env:DURATION } else { "30s" }

New-Item -ItemType Directory -Force -Path "benchmarks/raw" | Out-Null

foreach ($Strategy in $Strategies) {
    Write-Host "=== $Strategy ==="

    docker exec payment-lab-postgres psql -U cs -d payment_lab_dev -c `
        "UPDATE inventory SET available = $Stock, reserved = 0 WHERE product_id = $ProductId;"

    # 전략 전환은 재기동으로만 한다 (CLAUDE.md: 한 번에 한 개념만 켠다 — 핫스위치 없음).
    Stop-AppJava
    Start-Sleep -Seconds 2

    $proc = Start-Process -FilePath ".\gradlew.bat" `
        -ArgumentList "bootRun", "--args=""--inventory.lock-strategy=$Strategy""" `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "benchmarks/raw/$Strategy.bootrun.log" `
        -RedirectStandardError "benchmarks/raw/$Strategy.bootrun.err.log"

    try {
        Wait-AppReady

        k6 run `
            --env VUS=$Vus --env DURATION=$Duration --env PRODUCT_ID=$ProductId `
            --summary-export="benchmarks/raw/$Strategy.json" `
            k6/order-lock-benchmark.js
    } finally {
        Stop-AppJava
        if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    }
}

Write-Host "완료. benchmarks/raw/*.json을 읽어 benchmarks/01-lock-strategies.md 표를 채우세요."
