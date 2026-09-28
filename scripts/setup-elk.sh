#!/usr/bin/env bash
# 4.2: ILM 정책 + 인덱스 템플릿(데이터 스트림)을 로컬 Elasticsearch에 적용한다.
#
# `docker compose -f docker-compose.elk.yml up -d`를 실행하면 이제 `elk-setup` 1회성
# 컨테이너가 기동 시점에 같은 작업을 자동으로 해준다(CodeRabbit 리뷰, PR #101 —
# Filebeat가 이 등록보다 먼저 로그를 보내면 payment-logs가 일반 인덱스로 먼저 생겨버려서
# 나중에 템플릿을 등록해도 소급 적용되지 않는 문제가 있었다). 이 스크립트는 템플릿/ILM
# 정책을 바꾼 뒤 컨테이너 재기동 없이 수동으로 다시 적용하고 싶을 때 쓴다.
#
# docker-compose.elk.yml로 띄운 ES(4.1, xpack 보안 비활성화, localhost:9200)를 전제로 한다.
# 두 번 실행해도 안전하다(PUT은 있으면 덮어쓴다).
#
# `payment-logs`를 데이터 스트림으로 만든다 — 매일 새 인덱스를 수동으로 만드는 대신
# ES가 롤오버/삭제를 ILM 정책에 따라 알아서 처리한다. 실제 백킹 인덱스 이름은
# `.ds-payment-logs-...` 형태지만, Kibana Discover나 검색 쿼리에서는 그냥 `payment-logs`
# (또는 `payment-logs*`)로 찾으면 된다 — 로드맵의 `payment-logs-*` 표기는 이 패턴을
# 가리킨다.
set -euo pipefail

es_host=${1:-http://localhost:9200}
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
elk_dir="${script_dir}/../observability/elk"

echo "ES(${es_host})에 ILM 정책을 적용합니다..."
curl -sf -X PUT "${es_host}/_ilm/policy/payment-logs-ilm" \
  -H "Content-Type: application/json" \
  --data-binary "@${elk_dir}/ilm-policy-payment-logs.json" | (command -v jq >/dev/null && jq . || cat)

echo "ES(${es_host})에 인덱스 템플릿(데이터 스트림)을 적용합니다..."
curl -sf -X PUT "${es_host}/_index_template/payment-logs-template" \
  -H "Content-Type: application/json" \
  --data-binary "@${elk_dir}/index-template-payment-logs.json" | (command -v jq >/dev/null && jq . || cat)

echo
echo "완료. 확인:"
echo "  curl ${es_host}/_ilm/policy/payment-logs-ilm"
echo "  curl ${es_host}/_index_template/payment-logs-template"
echo "실제 로그가 들어오기 시작하면(4.3, Filebeat) 다음으로 데이터 스트림이 보입니다:"
echo "  curl ${es_host}/_data_stream/payment-logs"
