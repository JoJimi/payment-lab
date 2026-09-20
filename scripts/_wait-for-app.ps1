# 벤치마크 스크립트 공용 — 고정 sleep 대신 /actuator/health를 제한 시간 동안 폴링한다.
# bash 버전(_wait-for-app.sh)의 PowerShell 이식.

function Wait-AppReady {
    param(
        [string]$Url = "http://localhost:8080/actuator/health",
        [int]$TimeoutSeconds = 60
    )

    $waited = 0
    while ($true) {
        try {
            $resp = Invoke-RestMethod -Uri $Url -TimeoutSec 3 -ErrorAction Stop
            if ($resp.status -eq "UP") {
                Write-Host "앱 준비 완료 (${waited}초 대기)"
                return
            }
        } catch {
            # 아직 안 떠서 연결 실패하는 건 정상 — 계속 폴링
        }

        if ($waited -ge $TimeoutSeconds) {
            Write-Error "${TimeoutSeconds}초 안에 ${Url}이 UP 상태가 되지 않았습니다."
            exit 1
        }
        Start-Sleep -Seconds 1
        $waited++
    }
}

function Stop-AppJava {
    # pkill -f 'org.example.cs_study.CsStudyApplication'의 PowerShell 이식.
    # gradlew.bat -> Gradle daemon -> 실제 Spring Boot JVM으로 여러 겹 포크되므로,
    # PID를 직접 추적하는 대신 커맨드라인으로 앱 JVM을 정확히 찾아 죽인다.
    #
    # Stop-Process -Force는 종료 "요청"만 보내고 완료를 기다리지 않는다 — 호출 직후 바로
    # bootRun을 다시 띄우면 기존 JVM이 8080 포트를 아직 붙들고 있을 수 있어(CodeRabbit 지적),
    # Wait-Process로 실제 종료를 확인한 뒤 반환한다.
    $procIds = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*org.example.cs_study.CsStudyApplication*" } |
        ForEach-Object {
            Write-Host "기존 앱 프로세스 종료: PID $($_.ProcessId)"
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            $_.ProcessId
        }

    foreach ($procId in $procIds) {
        try {
            Wait-Process -Id $procId -Timeout 30 -ErrorAction SilentlyContinue
        } catch {
            # 이미 종료된 프로세스에 대한 Wait-Process 오류는 무시
        }
    }
}

function Assert-LastExitCode {
    # docker/k6 같은 외부 네이티브 명령은 $ErrorActionPreference = "Stop"로도 잡히지 않는다
    # (Windows PowerShell 5.1엔 $PSNativeCommandUseErrorActionPreference가 없음 — CodeRabbit 지적).
    # 호출부에서 매번 명시적으로 종료 코드를 확인해야 한다.
    param([Parameter(Mandatory = $true)][string]$Description)

    if ($LASTEXITCODE -ne 0) {
        throw "${Description} 실패 (exit code ${LASTEXITCODE})"
    }
}
