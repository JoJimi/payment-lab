# 로드맵 1.13/3.12 — 재고 락 4종(NONE/PESSIMISTIC/OPTIMISTIC/DISTRIBUTED) 성능 비교.
# 부록 C: "벤치마크 숫자가 노이즈" → 이 스크립트는 반드시 CI가 아닌 로컬 환경에서 돌릴 것.
# 워밍업 1회 + 측정 3회, 중앙값 (CLAUDE.md 측정 규칙).
# benchmark-lock-strategies.sh의 PowerShell 이식.
#
# 2.1(서비스 분리)로 InventoryService가 inventory-service 모듈로 옮겨갔다 — 옛 단일
# CsStudyApplication은 더 이상 존재하지 않는다. inventory-service를 직접 재기동하며
# InventoryController(3.12)의 동기 전용 엔드포인트(k6/inventory-lock-benchmark.js)를
# 두드려 락 전략 자체의 성능만 격리해서 잰다.
#
# 사용법 (PowerShell):
#   docker compose -f docker-compose.yml up -d
#   .\scripts\benchmark-lock-strategies.ps1
#
# 실행 후 benchmarks/raw/<전략>-run<N>.json의 k6 summary를 읽어
# benchmarks/01-lock-strategies.md 표를 채운다 (3회 중앙값 사용).

$ErrorActionPreference = "Stop"
. "$PSScriptRoot/_wait-for-app.ps1"
Import-DotEnv

$Strategies = @("NONE", "PESSIMISTIC", "OPTIMISTIC", "DISTRIBUTED")
$ProductId = if ($env:PRODUCT_ID) { $env:PRODUCT_ID } else { 1 }
# 재고 소진 자체가 목적이 아니다 — 중간에 바닥나면 이후 요청이 전부 409(빠른 실패)로 바뀌어
# 락 전략의 순수 지연시간 분포가 왜곡된다 (CodeRabbit 리뷰, PR #97).
$Stock = if ($env:STOCK) { $env:STOCK } else { 100000 }
$Vus = if ($env:VUS) { $env:VUS } else { 50 }
$Duration = if ($env:DURATION) { $env:DURATION } else { "30s" }
$InventoryServiceUrl = if ($env:INVENTORY_SERVICE_URL) { $env:INVENTORY_SERVICE_URL } else { "http://localhost:8083" }
$DbUsername = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { throw "DB_USERNAME이 필요합니다 — .env에 설정하세요" }

New-Item -ItemType Directory -Force -Path "benchmarks/raw" | Out-Null

function Reset-Inventory {
    # CodeRabbit 리뷰(PR #97) — 존재하지 않는 PRODUCT_ID에 대한 UPDATE는 오류 없이 0행을
    # 갱신한다. RETURNING으로 실제 갱신 행을 확인해 없으면 즉시 중단한다.
    $updatedProductId = docker exec payment-lab-postgres-inventory psql -U $DbUsername -d payment_lab_inventory -v ON_ERROR_STOP=1 -Atq -c `
        "UPDATE inventory SET available = $Stock, reserved = 0 WHERE product_id = $ProductId RETURNING product_id;"
    Assert-LastExitCode "재고 초기화"
    if ($updatedProductId -ne "$ProductId") {
        throw "inventory 행이 없습니다: product_id=$ProductId"
    }
}

foreach ($Strategy in $Strategies) {
    Write-Host "=== $Strategy ==="

    # 전략 전환은 재기동으로만 한다 (CLAUDE.md: 한 번에 한 개념만 켠다 — 핫스위치 없음).
    Stop-AppJava
    Start-Sleep -Seconds 2

    $proc = Start-Process -FilePath ".\gradlew.bat" `
        -ArgumentList "inventory-service:bootRun", "--args=""--inventory.lock-strategy=$Strategy""" `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "benchmarks/raw/$Strategy.bootrun.log" `
        -RedirectStandardError "benchmarks/raw/$Strategy.bootrun.err.log"

    try {
        Wait-AppReady -Url "$InventoryServiceUrl/actuator/health"

        Reset-Inventory
        Write-Host "--- 워밍업 ---"
        k6 run --env VUS=$Vus --env DURATION=10s --env PRODUCT_ID=$ProductId --env INVENTORY_SERVICE_URL=$InventoryServiceUrl k6/inventory-lock-benchmark.js | Out-Null
        Assert-LastExitCode "워밍업 k6 실행"

        for ($i = 1; $i -le 3; $i++) {
            # 워밍업/이전 측정이 재고를 소진시키므로 매 측정 전 동일 조건으로 재초기화한다.
            Reset-Inventory

            Write-Host "--- 측정 $i/3 ---"
            # k6 기본 summaryTrendStats엔 p99가 없다 — 3.12 종합 리포트가 p50/p99도 요구한다
            # (CodeRabbit 리뷰, PR #97).
            k6 run `
                --env VUS=$Vus --env DURATION=$Duration --env PRODUCT_ID=$ProductId --env INVENTORY_SERVICE_URL=$InventoryServiceUrl `
                --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" `
                --summary-export="benchmarks/raw/$Strategy-run$i.json" `
                k6/inventory-lock-benchmark.js
            Assert-LastExitCode "측정 $i k6 실행"
        }
    } finally {
        Stop-AppJava
        if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    }
}

Write-Host "완료. benchmarks/raw/<전략>-run*.json을 읽어 benchmarks/01-lock-strategies.md 표(3회 중앙값)를 채우세요."
