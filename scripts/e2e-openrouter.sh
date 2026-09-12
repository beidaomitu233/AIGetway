#!/bin/bash
# 轻享 AI 真实渠道全量验证（OpenRouter）：
# 配置(Provider/池/凭证/模型/Alias/候选) → 发布(实例协议) → /v1 同步+流式 → 管理端全站 API 冒烟
# 密钥经环境变量 OPENROUTER_API_KEY 注入，不写入本文件与任何仓库内容。
set -e
BASE=${BASE:-http://127.0.0.1:18080}
INTERNAL_TOKEN=${INTERNAL_TOKEN:-e2e-internal-token}
: "${OPENROUTER_API_KEY:?必须设置 OPENROUTER_API_KEY 环境变量}"
MODEL_NAME=${MODEL_NAME:-"nvidia/nemotron-3-ultra-550b-a55b:free"}
OUT=$(cygpath -m "${TEMP:-/tmp}")/e2e-or
mkdir -p "$OUT"

json_field() { python -c "
import json,sys
d=json.load(open(r'$1',encoding='utf-8'))
path='$2'.split('.')
v=d
for p in path:
    if p: v=v.get(p) if isinstance(v,dict) else None
print(v if v is not None else '')
"; }

echo "== 1. Provider（OpenRouter，OpenAI 兼容） =="
curl -s -X POST $BASE/admin/providers -H 'Content-Type: application/json' \
  -d '{"name":"openrouter","type":"OPENAI","base_url":"https://openrouter.ai/api/v1","connect_timeout_ms":10000,"read_timeout_ms":180000,"enabled":true,"default_headers":{"HTTP-Referer":"https://light-ai.local","X-Title":"Light AI"}}' \
  -o $OUT/provider.json
PROVIDER_ID=$(json_field $OUT/provider.json data.id)
[ -z "$PROVIDER_ID" ] && { echo "PROVIDER FAILED:"; cat $OUT/provider.json; exit 1; }
echo "provider=$PROVIDER_ID"

echo "== 2. 凭证池 =="
curl -s -X POST $BASE/admin/credential-pools -H 'Content-Type: application/json' \
  -d "{\"provider_id\":\"$PROVIDER_ID\",\"name\":\"openrouter-pool\",\"selection_strategy\":\"WEIGHTED_RANDOM\",\"enabled\":true}" \
  -o $OUT/pool.json
POOL_ID=$(json_field $OUT/pool.json data.id)
[ -z "$POOL_ID" ] && { echo "POOL FAILED:"; cat $OUT/pool.json; exit 1; }
echo "pool=$POOL_ID"

echo "== 3. 凭证（真实 Key，AES-GCM 加密落库） =="
curl -s -X POST $BASE/admin/credential-pools/$POOL_ID/credentials -H 'Content-Type: application/json' \
  -d "{\"name\":\"or-key-1\",\"secret_source\":\"INLINE_ENCRYPTED\",\"secret_value\":\"$OPENROUTER_API_KEY\",\"weight\":100,\"rpm_limit\":20,\"enabled\":true}" \
  -o $OUT/cred.json
CRED_ID=$(json_field $OUT/cred.json data.id)
[ -z "$CRED_ID" ] && { echo "CREDENTIAL FAILED:"; cat $OUT/cred.json; exit 1; }
echo "credential=$CRED_ID"

echo "== 4. 模型（真实 model_id） =="
curl -s -X POST $BASE/admin/providers/$PROVIDER_ID/models -H 'Content-Type: application/json' \
  -d "{\"model_id\":\"$MODEL_NAME\",\"display_name\":\"Nemotron Ultra 550B Free\",\"tokenizer_family\":\"cl100k\",\"context_window\":131072,\"max_output_tokens\":8192,
        \"support_stream\":true,\"support_system_message\":true,\"support_temperature\":true,\"support_top_p\":true,\"support_stop\":true,
        \"temperature_min\":0,\"temperature_max\":2,\"top_p_min\":0,\"top_p_max\":1,\"max_stop_sequences\":4,
        \"default_temperature\":0.7,\"default_max_tokens\":256,
        \"input_price\":\"0\",\"output_price\":\"0\",\"price_unit\":1000,\"currency\":\"USD\",\"enabled\":true}" \
  -o $OUT/model.json
MODEL_ID=$(json_field $OUT/model.json data.id)
REAL_MODEL_ID=$(json_field $OUT/model.json data.entity.model_id)
[ -z "$MODEL_ID" ] && { echo "MODEL FAILED:"; cat $OUT/model.json; exit 1; }
echo "model=$MODEL_ID model_id=$REAL_MODEL_ID"

echo "== 5. Alias + 候选 + 启用 =="
curl -s -X POST $BASE/admin/model-aliases -H 'Content-Type: application/json' \
  -d '{"alias":"chat-live","display_name":"线上对话","enabled":false}' -o $OUT/alias.json
ALIAS_ID=$(json_field $OUT/alias.json data.id)
[ -z "$ALIAS_ID" ] && { echo "ALIAS FAILED:"; cat $OUT/alias.json; exit 1; }
curl -s -X POST $BASE/admin/model-aliases/$ALIAS_ID/candidates -H 'Content-Type: application/json' \
  -d "{\"provider_model_id\":\"$MODEL_ID\",\"credential_pool_id\":\"$POOL_ID\",\"priority\":10,\"weight\":100,\"enabled\":true}" \
  -o $OUT/cand.json
[ -z "$(json_field $OUT/cand.json data.id)" ] && { echo "CANDIDATE FAILED:"; cat $OUT/cand.json; exit 1; }
ALIAS_VERSION=$(json_field $OUT/alias.json data.version)
curl -s -X POST $BASE/admin/model-aliases/$ALIAS_ID/enable -H 'Content-Type: application/json' \
  -d "{\"version\":$ALIAS_VERSION}" -o $OUT/enable.json
echo "alias=$ALIAS_ID enabled"

echo "== 6. 实例心跳注册 =="
INSTANCE_ID=$(python -c "import uuid; print(uuid.uuid4())")
HBEAT() {
  curl -s -X POST $BASE/internal/runtime-instances/heartbeat \
    -H 'Content-Type: application/json' \
    -H "X-Light-AI-Instance-Token: $INTERNAL_TOKEN" -H "X-Light-AI-Instance-Id: $INSTANCE_ID" \
    -d "{\"instance_id\":\"$INSTANCE_ID\",\"runtime_mode\":\"STANDALONE_SERVER\",\"runtime_version\":\"0.1.0\",\"application\":\"light-ai-server\",\"zone\":\"local\",\"supported_schema_versions\":[\"1\"],\"loaded_adapter_types\":[\"OPENAI\",\"ANTHROPIC\",\"GEMINI\",\"DEEPSEEK\"],\"active_snapshot_no\":0,\"accepting_requests\":true,\"reported_at\":null}"
}
HBEAT > $OUT/hb.json > /dev/null 2>&1 || true
HBEAT > /dev/null
echo "instance=$INSTANCE_ID"

echo "== 7. 校验并发布 =="
curl -s $BASE/admin/config/draft-state -o $OUT/draft.json
DRAFT_REVISION=$(json_field $OUT/draft.json data.draft_revision)
curl -s -X POST $BASE/admin/config/validate -H 'Content-Type: application/json' \
  -d "{\"draft_revision\":$DRAFT_REVISION}" -o $OUT/validate.json
VALIDATION_ID=$(json_field $OUT/validate.json data.validation_id)
[ -z "$VALIDATION_ID" ] && { echo "VALIDATE FAILED:"; cat $OUT/validate.json; exit 1; }
WARN_IDS=$(python -c "
import json
d=json.load(open(r'$OUT/validate.json',encoding='utf-8'))
issues=d.get('data',{}).get('issues',[]) or []
print(json.dumps([i.get('code') for i in issues if (i.get('severity') or '').upper()=='WARNING']))
")
curl -s -X POST $BASE/admin/config/publish -H 'Content-Type: application/json' \
  -d "{\"validation_id\":\"$VALIDATION_ID\",\"draft_revision\":$DRAFT_REVISION,\"acknowledged_warning_ids\":$WARN_IDS,\"publish_note\":\"openrouter-e2e\"}" \
  -o $OUT/publish.json
echo "publish=$(head -c 120 $OUT/publish.json)"

echo "== 8. 实例准备→激活 =="
for i in 1 2 3 4 5 6 7 8; do
  HBEAT > $OUT/hb.json
  PREPARE=$(json_field $OUT/hb.json data.prepare_command.publish_id)
  ACTIVATE=$(json_field $OUT/hb.json data.activation_command.publish_id)
  TARGET_SNAP=$(json_field $OUT/hb.json data.prepare_command.snapshot_no)
  [ -z "$TARGET_SNAP" ] && TARGET_SNAP=$(json_field $OUT/hb.json data.activation_command.snapshot_no)
  [ -z "$TARGET_SNAP" ] && TARGET_SNAP=$(json_field $OUT/hb.json data.active_snapshot_no)
  if [ -n "$PREPARE" ] && [ "$PREPARE" != "None" ]; then
    curl -s -X POST $BASE/internal/publish-records/$PREPARE/instances/$INSTANCE_ID/reports \
      -H 'Content-Type: application/json' -H "X-Light-AI-Instance-Token: $INTERNAL_TOKEN" -H "X-Light-AI-Instance-Id: $INSTANCE_ID" \
      -d '{"target_snapshot_no":'"$TARGET_SNAP"',"status":"READY","reported_at":null,"retry_count":0,"load_duration_ms":10,"error_code":null,"error_summary":null}' > /dev/null
    echo "loop$i READY(target=$TARGET_SNAP)"
  elif [ -n "$ACTIVATE" ] && [ "$ACTIVATE" != "None" ]; then
    curl -s -X POST $BASE/internal/publish-records/$ACTIVATE/instances/$INSTANCE_ID/reports \
      -H 'Content-Type: application/json' -H "X-Light-AI-Instance-Token: $INTERNAL_TOKEN" -H "X-Light-AI-Instance-Id: $INSTANCE_ID" \
      -d '{"target_snapshot_no":'"$TARGET_SNAP"',"status":"LOADED","reported_at":null,"retry_count":0,"load_duration_ms":10,"error_code":null,"error_summary":null}' > /dev/null
    echo "loop$i LOADED(target=$TARGET_SNAP)"
    HBEAT > /dev/null
    break
  else
    echo "loop$i idle(active=$(json_field $OUT/hb.json data.active_snapshot_no))"
    P=$(json_field $OUT/publish.json data.status)
    [ "$P" = "SUCCEEDED" ] && break
  fi
  sleep 1
done
STATUS=$(json_field $OUT/publish.json data.status)
echo "publish_status=$STATUS"

echo "== 9. 就绪检查 =="
curl -s $BASE/health/ready -o $OUT/ready.json -w "ready_http=%{http_code}\n"; cat $OUT/ready.json; echo

echo "== 10. Access Token =="
curl -s -X POST $BASE/admin/access-credentials -H 'Content-Type: application/json' \
  -d "{\"name\":\"live-token\",\"application\":\"live-app\",\"allowed_alias_ids\":[\"$ALIAS_ID\"],\"ip_allowlist\":[],\"enabled\":true}" \
  -o $OUT/token.json
BEARER=$(json_field $OUT/token.json data.token_value)
[ -z "$BEARER" ] && { echo "TOKEN FAILED:"; cat $OUT/token.json; exit 1; }
echo "token acquired (hidden)"

echo "== 11. /v1/models =="
curl -s $BASE/v1/models -H "Authorization: Bearer $BEARER" -o $OUT/models.json -w "models_http=%{http_code}\n"
python -c "
import json
d=json.load(open(r'$OUT/models.json',encoding='utf-8'))
print('models:', [m.get('id') for m in d.get('data',[])])
"

echo "== 12. /v1/chat/completions 同步（真实 OpenRouter 调用） =="
curl -s -m 170 -X POST $BASE/v1/chat/completions -H "Authorization: Bearer $BEARER" \
  -H 'Content-Type: application/json' \
  -d '{"model":"chat-live","stream":false,"max_tokens":64,"messages":[{"role":"user","content":"用一句话介绍你自己"}]}' \
  -o $OUT/chat.json -w "chat_http=%{http_code}\n"
python -c "
import json
d=json.load(open(r'$OUT/chat.json',encoding='utf-8'))
if 'error' in d: print('SYNC ERROR:', d['error'].get('code'), str(d['error'].get('message'))[:200])
else:
    print('content=', str(d['choices'][0]['message']['content'])[:120].replace(chr(10),' '))
    print('usage=', d.get('usage'))
    print('cost=', d.get('light_ai',{}).get('cost'), 'trace=', d.get('light_ai',{}).get('trace_id'))
"

echo "== 13. /v1/chat/completions 流式（真实 SSE） =="
curl -s -N -m 170 -X POST $BASE/v1/chat/completions -H "Authorization: Bearer $BEARER" \
  -H 'Content-Type: application/json' \
  -d '{"model":"chat-live","stream":true,"max_tokens":64,"messages":[{"role":"user","content":"数到三"}]}' \
  -o $OUT/stream.txt -w "stream_http=%{http_code}\n"
python -c "
raw=open(r'$OUT/stream.txt',encoding='utf-8',errors='replace').read()
events=[l for l in raw.splitlines() if l.startswith('data:')]
dones=[l for l in events if '[DONE]' in l]
errs=[l for l in events if '\"error\"' in l]
delta_len=sum(len(l) for l in events if '\"delta\"' in l)
print('sse_events=%d delta_bytes=%d done=%s error=%s' % (len(events), delta_len, bool(dones), bool(errs)))
print('first_frame=', (events[0][:160] if events else 'NONE'))
"

echo "== 14. 管理端全站 API 冒烟 =="
for ep in "providers" "credential-pools" "model-aliases" "config/draft-state" "config/publish-records" "runtime-instances" "traces?start_at=2026-09-01T00:00:00Z&end_at=2026-09-30T00:00:00Z" "overview/summary?start_at=2026-09-01T00:00:00Z&end_at=2026-09-30T00:00:00Z" "usage/summary?start_at=2026-09-01T00:00:00Z&end_at=2026-09-30T00:00:00Z" "audit-logs?page=1&page_size=5" "access-credentials" "developer-access/context" "runtime-config" "reliability-policies" "limit-policies" "circuits"; do
  CODE=$(curl -s -o $OUT/smoke.json -w "%{http_code}" "$BASE/admin/$ep")
  echo "GET /admin/$ep -> $CODE"
done

echo "== 15. Trace 与 Usage 落库核对 =="
curl -s "$BASE/admin/traces?start_at=2026-09-01T00:00:00Z&end_at=2026-09-30T00:00:00Z" -o $OUT/traces.json
python -c "
import json
d=json.load(open(r'$OUT/traces.json',encoding='utf-8'))
items=d.get('data',{}).get('items',[])
print('traces=', len(items), 'first_status=', (items[0].get('status') if items else '-'))
"
echo "E2E-OPENROUTER DONE"
