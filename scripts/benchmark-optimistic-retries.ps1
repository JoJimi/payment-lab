# 로드맵 1.14/3.12 — 낙관적 락 재시도 횟수(1/3/5/10)별 경합 성능 곡선.
# 1.13과 같은 k6 시나리오를 재사용하되, inventory.lock-strategy는 OPTIMISTIC으로 고정하고
# inventory.optimistic-lock.max-retries만 바꿔가며 측정한다 (한 번에 한 개념만 켠다).
# 워밍업 1회 + 측정 3회, 중앙값 (CLAUDE.md 측정 규칙).
# benchmark-optimistic-retries.sh의 PowerShell 이식.
#
# 사용법 (PowerShell):
#   docker compose -f docker-compose.yml up -d
#   .\scripts\benchmark-optimistic-retries.ps1

$ErrorActionPreference = "Stop"
. "$PSScriptRoot/_wait-for-app.ps1"
Import-DotEnv

$RetryCounts = @(1, 3, 5, 10)
$ProductId = if ($env:PRODUCT_ID) { $env:PRODUCT_ID } else { 1 }
# 재고 소진 자체가 목적이 아니다 — benchmark-lock-strategies.ps1과 같은 이유로 넉넉하게 잡는다
# (CodeRabbit 리뷰, PR #97).
$Stock = if ($env:STOCK) { $env:STOCK } else { 100000 }
$Vus = if ($env:VUS) { $env:VUS } else { 50 }
$Duration = if ($env:DURATION) { $env:DURATION } else { "30s" }
$InventoryServiceUrl = if ($env:INVENTORY_SERVICE_URL) { $env:INVENTORY_SERVICE_URL } else { "http://localhost:8083" }
$DbUsername = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { throw "DB_USERNAME이 필요합니다 — .env에 설정하세요" }

New-Item -ItemType Directory -Force -Path "benchmarks/raw" | Out-Null

function Reset-Inventory {
    # CodeRabbit 리뷰(PR #97) — benchmark-lock-strategies.ps1과 동일한 이유로 RETURNING 확인.
    $updatedProductId = docker exec payment-lab-postgres-inventory psql -U $DbUsername -d payment_lab_inventory -v ON_ERROR_STOP=1 -Atq -c `
        "UPDATE inventory SET available = $Stock, reserved = 0 WHERE product_id = $ProductId RETURNING product_id;"
    Assert-LastExitCode "재고 초기화"
    if ($updatedProductId -ne "$ProductId") {
        throw "inventory 행이 없습니다: product_id=$ProductId"
    }
}

foreach ($Retries in $RetryCounts) {
    Write-Host "=== max-retries=$Retries ==="

    Stop-AppJava
    Start-Sleep -Seconds 2

    $proc = Start-Process -FilePath ".\gradlew.bat" `
        -ArgumentList "inventory-service:bootRun", "--args=""--spring.profiles.active=dev,benchmark --inventory.lock-strategy=OPTIMISTIC --inventory.optimistic-lock.max-retries=$Retries""" `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "benchmarks/raw/optimistic-retries-$Retries.bootrun.log" `
        -RedirectStandardError "benchmarks/raw/optimistic-retries-$Retries.bootrun.err.log"

    try {
        Wait-AppReady -Url "$InventoryServiceUrl/actuator/health"

        Reset-Inventory
        Write-Host "--- 워밍업 ---"
        k6 run --env VUS=$Vus --env DURATION=10s --env PRODUCT_ID=$ProductId --env INVENTORY_SERVICE_URL=$InventoryServiceUrl k6/inventory-lock-benchmark.js | Out-Null
        Assert-LastExitCode "워밍업 k6 실행"

        for ($i = 1; $i -le 3; $i++) {
            Reset-Inventory

            Write-Host "--- 측정 $i/3 ---"
            # k6 기본 summaryTrendStats엔 p99가 없다 — 3.12 종합 리포트가 p50/p99도 요구한다
            # (CodeRabbit 리뷰, PR #97).
            k6 run `
                --env VUS=$Vus --env DURATION=$Duration --env PRODUCT_ID=$ProductId --env INVENTORY_SERVICE_URL=$InventoryServiceUrl `
                --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" `
                --summary-export="benchmarks/raw/optimistic-retries-$Retries-run$i.json" `
                k6/inventory-lock-benchmark.js
            Assert-LastExitCode "측정 $i k6 실행"
        }
    } finally {
        Stop-AppJava
        if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    }
}

Write-Host "완료. benchmarks/raw/optimistic-retries-*-run*.json을 읽어 benchmarks/02-optimistic-retry-curve.md 표(3회 중앙값)를 채우세요."
