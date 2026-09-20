# 로드맵 1.21 — 베이스라인 측정. 이후 모든 개선(캐싱, 서킷 브레이커, MSA 전환)은 이 숫자와 비교된다.
# 워밍업 1회 + 측정 3회, 중앙값을 benchmarks/03-baseline.md에 직접 기록할 것 (CLAUDE.md 측정 규칙).
# measure-baseline.sh의 PowerShell 이식 (Docker Desktop Windows 엔진 + gradlew.bat + k6 네이티브).
#
# 사용법 (PowerShell):
#   docker compose -f docker-compose.yml up -d
#   Start-Process powershell -ArgumentList '-NoExit','-Command','.\gradlew.bat mockPgRun'
#   Start-Process powershell -ArgumentList '-NoExit','-Command','.\gradlew.bat bootRun'
#   .\scripts\measure-baseline.ps1

$ErrorActionPreference = "Stop"
. "$PSScriptRoot/_wait-for-app.ps1"

$ProductId = if ($env:PRODUCT_ID) { $env:PRODUCT_ID } else { 1 }
$Stock = if ($env:STOCK) { $env:STOCK } else { 100000 } # 베이스라인은 재고 소진이 목적이 아니므로 넉넉하게
$Vus = if ($env:VUS) { $env:VUS } else { 20 }
$Duration = if ($env:DURATION) { $env:DURATION } else { "60s" }

New-Item -ItemType Directory -Force -Path "benchmarks/raw" | Out-Null

function Reset-Inventory {
    docker exec payment-lab-postgres psql -U cs -d payment_lab_dev -c `
        "UPDATE inventory SET available = $Stock, reserved = 0 WHERE product_id = $ProductId;"
    Assert-LastExitCode "재고 초기화"
}

# 사용법상 bootRun을 별도 터미널에서 직접 띄우므로, k6를 쏘기 전에 실제로 UP인지 확인한다
# (CodeRabbit 지적 — 안 그러면 앱이 아직 기동 중일 때 워밍업/첫 측정이 연결 실패로 오염될 수 있음).
Wait-AppReady

Reset-Inventory

Write-Host "=== 워밍업 ==="
k6 run --env VUS=$Vus --env DURATION=10s --env PRODUCT_ID=$ProductId k6/order-payment-flow.js | Out-Null
Assert-LastExitCode "워밍업 k6 실행"

for ($i = 1; $i -le 3; $i++) {
    # 워밍업과 이전 측정이 재고를 소모하므로, 매 측정 전에 동일한 조건으로 재초기화한다
    # (CodeRabbit 지적 — 초기화를 한 번만 하면 뒤로 갈수록 다른 조건에서 측정하게 됨).
    Reset-Inventory

    Write-Host "=== 측정 $i/3 ==="
    k6 run `
        --env VUS=$Vus --env DURATION=$Duration --env PRODUCT_ID=$ProductId `
        --summary-export="benchmarks/raw/baseline-run$i.json" `
        k6/order-payment-flow.js
    Assert-LastExitCode "측정 $i k6 실행"
}

Write-Host "완료. benchmarks/raw/baseline-run*.json 3개의 중앙값을 benchmarks/03-baseline.md에 기록하세요."
