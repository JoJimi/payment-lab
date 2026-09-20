#!/usr/bin/env bash
# 벤치마크 스크립트 공용 — 고정 sleep 대신 /actuator/health를 제한 시간 동안 폴링한다.
# bootRun 직후 바로 k6를 쏘면 앱이 아직 안 떠서 연결 실패가 나는데, 그걸 "락 전략 탓"으로
# 오해하지 않기 위함.

wait_for_app_ready() {
  local url=${1:-http://localhost:8080/actuator/health}
  local timeout_seconds=${2:-60}
  local waited=0

  until curl -sf "${url}" | grep -q '"status":"UP"'; do
    if [ "${waited}" -ge "${timeout_seconds}" ]; then
      echo "::error:: ${timeout_seconds}초 안에 ${url}이 UP 상태가 되지 않았습니다." >&2
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "앱 준비 완료 (${waited}초 대기)"
}
