#!/usr/bin/env bash
# 2.16: <토픽>-dlt에 쌓인 메시지를 원본 토픽으로 그대로 되돌린다.
#
# 반드시 아래 둘 다 하고 실행할 것(CodeRabbit 리뷰, PR #74 — 안 그러면 재발행한 레코드가
# 또 실패해 DLT에 또 쌓이는 걸 이 실행 안에서 또 읽어버릴 수 있다):
#   1. 원인을 먼저 고친다 — 코드 버그면 배포, 인프라 장애면 복구.
#   2. 원본 토픽을 구독 중인 컨슈머(서비스)를 잠깐 멈춘다 — 재발행 중에 컨슈머가 살아있으면
#      "재발행 → 즉시 재소비 → (원인이 덜 고쳐졌으면) 재실패 → 같은 DLT에 또 쌓임"이
#      이 스크립트 한 번 실행(약 5초) 안에서도 일어날 수 있다. 서비스가 꺼져 있으면
#      재발행은 그냥 원본 토픽에 쌓이기만 하고, 서비스를 다시 켰을 때 한 번에 정상 소비된다.
#
# 이 스크립트는 로컬 학습용 도구다 — 키/값을 탭/개행으로 구분되는 텍스트로 다루므로 키에
# 탭이나 값에 개행이 섞이면 레코드가 깨질 수 있다(CodeRabbit 리뷰, PR #74). 이 프로젝트의
# 실제 페이로드(EventEnvelope JSON, docs/architecture/event-catalog.md)는 키가 orderId
# 숫자 문자열이고 값은 Jackson이 한 줄로 직렬화한 압축 JSON이라 이 경계에 걸리지 않는다 —
# 바이트 그대로 옮기는 제대로 된 Kafka 클라이언트 도구를 새로 만드는 건 이 프로젝트 규모
# (로컬 단일 브로커, 학습 목적)에 비해 과한 작업이라 의도적으로 하지 않았다.
#
# 로컬 docker-compose Kafka(payment-lab-kafka, docker-compose.yml)를 전제로 한다.
set -euo pipefail

topic=${1:?"사용법: $0 <원본-토픽> [Kafka 컨테이너 이름=payment-lab-kafka]"}
container=${2:-payment-lab-kafka}
dlt_topic="${topic}-dlt"

echo "${dlt_topic} -> ${topic} 재발행을 시작합니다 (컨테이너: ${container})"
echo "먼저 원인을 고치고 원본 토픽 컨슈머(서비스)를 멈췄는지 확인하세요 — 안 했다면 Ctrl+C로 중단하세요."

# 키/값을 탭으로 구분해 그대로 옮긴다. DLT 레코드에 붙는 예외 정보 헤더(원본 토픽,
# 예외 메시지, 스택트레이스 등)는 재발행 시 버려진다 — 원본 컨슈머는 그 헤더를 보지 않으므로
# 문제 없다. --timeout-ms로 더 읽을 메시지가 없으면 일정 시간 뒤 스스로 종료한다(예외
# 스택트레이스는 stderr로 나가 조용히 버린다 — 정상 종료 경로다).
docker exec -i "${container}" /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic "${dlt_topic}" \
  --from-beginning \
  --timeout-ms 5000 \
  --property print.key=true \
  --property key.separator=$'\t' \
  2>/dev/null |
  docker exec -i "${container}" /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic "${topic}" \
    --property parse.key=true \
    --property key.separator=$'\t'

echo "완료. ${dlt_topic}의 원본 메시지는 재발행 후에도 그대로 남아있습니다 — 확인 후 필요하면 직접 지우세요."
