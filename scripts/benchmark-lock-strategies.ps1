# 로드맵 1.13 — 재고 락 4종(NONE/PESSIMISTIC/OPTIMISTIC/DISTRIBUTED) 성능 비교.
# 부록 C: "벤치마크 숫자가 노이즈" → 이 스크립트는 반드시 CI가 아닌 로컬 환경에서 돌릴 것.
# 워밍업 1회 + 측정 3회, 중앙값 (CLAUDE.md 측정 규칙).
# benchmark-lock-strategies.sh의 PowerShell 이식.
#
# 사용법 (PowerShell):
#   docker compose -f docker-compose.yml up -d
#   Start-Process powershell -ArgumentList '-NoExit','-Command','.\gradlew.bat mockPgRun'
#   .\scripts\benchmark-lock-strategies.ps1
#
# 실행 후 benchmarks/raw/<전략>-run<N>.json의 k6 summary를 읽어
# benchmarks/01-lock-strategies.md 표를 채운다 (3회 중앙값 사용).

$ErrorActionPreference = "Stop"
. "$PSScriptRoot/_wait-for-app.ps1"

$Strategies = @("NONE", "PESSIMISTIC", "OPTIMISTIC", "DISTRIBUTED")
$ProductId = if ($env:PRODUCT_ID) { $env:PRODUCT_ID } else { 1 }
$Stock = if ($env:STOCK) { $env:STOCK } else { 100 }
$Vus = if ($env:VUS) { $env:VUS } else { 50 }
$Duration = if ($env:DURATION) { $env:DURATION } else { "30s" }

New-Item -ItemType Directory -Force -Path "benchmarks/raw" | Out-Null

function Reset-Inventory {
    docker exec payment-lab-postgres psql -U cs -d payment_lab_dev -c `
        "UPDATE inventory SET available = $Stock, reserved = 0 WHERE product_id = $ProductId;"
    Assert-LastExitCode "재고 초기화"
}

foreach ($Strategy in $Strategies) {
    Write-Host "=== $Strategy ==="

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

        Reset-Inventory
        Write-Host "--- 워밍업 ---"
        k6 run --env VUS=$Vus --env DURATION=10s --env PRODUCT_ID=$ProductId k6/order-lock-benchmark.js | Out-Null
        Assert-LastExitCode "워밍업 k6 실행"

        for ($i = 1; $i -le 3; $i++) {
            # 워밍업/이전 측정이 재고를 소진시키므로 매 측정 전 동일 조건으로 재초기화한다.
            Reset-Inventory

            Write-Host "--- 측정 $i/3 ---"
            k6 run `
                --env VUS=$Vus --env DURATION=$Duration --env PRODUCT_ID=$ProductId `
                --summary-export="benchmarks/raw/$Strategy-run$i.json" `
                k6/order-lock-benchmark.js
            Assert-LastExitCode "측정 $i k6 실행"
        }
    } finally {
        Stop-AppJava
        if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    }
}

Write-Host "완료. benchmarks/raw/<전략>-run*.json을 읽어 benchmarks/01-lock-strategies.md 표(3회 중앙값)를 채우세요."
