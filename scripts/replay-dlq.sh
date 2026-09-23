#!/usr/bin/env bash
# 2.16: <토픽>-dlt에 쌓인 메시지를 원본 토픽으로 그대로 되돌린다.
#
# 반드시 원인을 먼저 고친 뒤에 실행할 것 — 이 스크립트는 재발행만 한다. 원인이 그대로면
# 컨슈머가 다시 실패하고, 재시도(500ms 간격 2회, common-kafka KafkaErrorHandlerConfig)를
# 또 소진해 같은 메시지가 DLT에 또 쌓인다(무한 루프는 아니다 — 매번 사람이 이 스크립트를
# 실행해야만 재시도가 일어난다).
#
# 로컬 docker-compose Kafka(payment-lab-kafka, docker-compose.yml)를 전제로 한다.
set -euo pipefail

topic=${1:?"사용법: $0 <원본-토픽> [Kafka 컨테이너 이름=payment-lab-kafka]"}
container=${2:-payment-lab-kafka}
dlt_topic="${topic}-dlt"

echo "${dlt_topic} -> ${topic} 재발행을 시작합니다 (컨테이너: ${container})"
echo "먼저 원인을 고쳤는지 확인하세요 — 안 고쳤다면 Ctrl+C로 중단하세요."

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
