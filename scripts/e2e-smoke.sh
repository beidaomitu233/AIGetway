#!/bin/bash
# 轻享 AI Standalone Server 端到端冒烟脚本：
# 配置(Provider/池/凭证/模型/Alias/候选) → 校验 → 发布(实例协议) → /v1 调用
# 依赖：服务已在 18080 启动（trusted-local 或 X-Admin-Token），内部口令通过 INTERNAL_TOKEN 传入。
set -e
BASE=${BASE:-http://127.0.0.1:18080}
INTERNAL_TOKEN=${INTERNAL_TOKEN:-}
INSTANCE_ID=$(python -c "import uuid; print(uuid.uuid4())")
OUT=$(cygpath -m "${TEMP:-/tmp}")/e2e-out
mkdir -p "$OUT" 2>/dev/null || OUT=/c/Users/12062/AppData/Local/Temp/e2e-out

json_field() { python -c "
import json,sys
d=json.load(open(r'$1',encoding='utf-8'))
path='$2'.split('.')
v=d
for p in path:
    if p: v=v.get(p) if isinstance(v,dict) else None
print(v if v is not None else '')
"; }

echo "== 1. 创建 Provider =="
curl -s -X POST $BASE/admin/providers -H 'Content-Type: application/json' \
  -d '{"name":"stub-provider","type":"OPENAI","base_url":"http://127.0.0.1:19099/v1","connect_timeout_ms":3000,"read_timeout_ms":20000,"enabled":true}' \
  -o $OUT/e2e-provider.json
PROVIDER_ID=$(json_field $OUT/e2e-provider.json data.id)
[ -z "$PROVIDER_ID" ] && { echo "PROVIDER FAILED:"; cat $OUT/e2e-provider.json; exit 1; }
echo "provider=$PROVIDER_ID"

echo "== 2. 创建凭证池 =="
curl -s -X POST $BASE/admin/credential-pools -H 'Content-Type: application/json' \
  -d "{\"provider_id\":\"$PROVIDER_ID\",\"name\":\"stub-pool\",\"selection_strategy\":\"WEIGHTED_RANDOM\",\"enabled\":true}" \
  -o $OUT/e2e-pool.json
POOL_ID=$(json_field $OUT/e2e-pool.json data.id)
[ -z "$POOL_ID" ] && { echo "POOL FAILED:"; cat $OUT/e2e-pool.json; exit 1; }
echo "pool=$POOL_ID"

echo "== 3. 创建凭证 =="
curl -s -X POST $BASE/admin/credential-pools/$POOL_ID/credentials -H 'Content-Type: application/json' \
  -d '{"name":"stub-key","secret_source":"INLINE_ENCRYPTED","secret_value":"sk-stub-1234567890","weight":100,"enabled":true}' \
  -o $OUT/e2e-cred.json
CRED_ID=$(json_field $OUT/e2e-cred.json data.id)
[ -z "$CRED_ID" ] && { echo "CREDENTIAL FAILED:"; cat $OUT/e2e-cred.json; exit 1; }
echo "credential=$CRED_ID"

echo "== 4. 创建模型 =="
curl -s -X POST $BASE/admin/providers/$PROVIDER_ID/models -H 'Content-Type: application/json' \
  -d '{"display_name":"Stub Model","tokenizer_family":"cl100k","context_window":8192,"max_output_tokens":4096,
        "support_stream":true,"support_system_message":true,"support_temperature":true,"support_top_p":true,"support_stop":true,
        "temperature_min":0,"temperature_max":2,"top_p_min":0,"top_p_max":1,"max_stop_sequences":4,
        "default_temperature":0.7,"default_max_tokens":1024,
        "input_price":"0.000001","output_price":"0.000002","price_unit":1000,"currency":"USD","enabled":true}' \
  -o $OUT/e2e-model.json
MODEL_ID=$(json_field $OUT/e2e-model.json data.id)
[ -z "$MODEL_ID" ] && { echo "MODEL FAILED:"; cat $OUT/e2e-model.json; exit 1; }
echo "model=$MODEL_ID"

echo "== 5. 创建 Alias =="
curl -s -X POST $BASE/admin/model-aliases -H 'Content-Type: application/json' \
  -d '{"alias":"chat-demo","display_name":"演示对话","enabled":false}' \
  -o $OUT/e2e-alias.json
ALIAS_ID=$(json_field $OUT/e2e-alias.json data.id)
[ -z "$ALIAS_ID" ] && { echo "ALIAS FAILED:"; cat $OUT/e2e-alias.json; exit 1; }
echo "alias=$ALIAS_ID"

echo "== 6. 创建候选并启用 Alias =="
curl -s -X POST $BASE/admin/model-aliases/$ALIAS_ID/candidates -H 'Content-Type: application/json' \
  -d "{\"provider_model_id\":\"$MODEL_ID\",\"credential_pool_id\":\"$POOL_ID\",\"priority\":10,\"weight\":100,\"enabled\":true}" \
  -o $OUT/e2e-cand.json
CAND_ID=$(json_field $OUT/e2e-cand.json data.id)
[ -z "$CAND_ID" ] && { echo "CANDIDATE FAILED:"; cat $OUT/e2e-cand.json; exit 1; }
ALIAS_VERSION=$(json_field $OUT/e2e-alias.json data.version)
curl -s -X POST $BASE/admin/model-aliases/$ALIAS_ID/enable -H 'Content-Type: application/json'   -d "{\"version\":$ALIAS_VERSION}" -o $OUT/e2e-enable.json
echo "candidate=$CAND_ID enable=$(head -c 80 $OUT/e2e-enable.json)"

echo "== 7. 实例心跳注册（发布前置） =="
HBEAT() {
  curl -s -X POST $BASE/internal/runtime-instances/heartbeat \
    -H 'Content-Type: application/json' \
    -H "X-Light-AI-Instance-Token: $INTERNAL_TOKEN" -H "X-Light-AI-Instance-Id: $INSTANCE_ID" \
    -d "{\"instance_id\":\"$INSTANCE_ID\",\"runtime_mode\":\"STANDALONE_SERVER\",\"runtime_version\":\"0.1.0\",\"application\":\"light-ai-server\",\"zone\":\"local\",\"supported_schema_versions\":[\"1\"],\"loaded_adapter_types\":[\"OPENAI\",\"ANTHROPIC\",\"GEMINI\",\"DEEPSEEK\"],\"active_snapshot_no\":0,\"accepting_requests\":true,\"reported_at\":\"2026-09-07T00:00:00+08:00\"}"
}
HBEAT > $OUT/e2e-hb.json
echo "heartbeat=$(head -c 200 $OUT/e2e-hb.json)"

echo "== 8. 校验并发布 =="
DRAFT_REVISION=$(json_field $OUT/e2e-hb.json data.draftRevision)
curl -s $BASE/admin/config/draft-state -o $OUT/e2e-draft.json
DRAFT_REVISION=$(json_field $OUT/e2e-draft.json data.draft_revision)
[ -z "$DRAFT_REVISION" ] && { echo "DRAFT STATE FAILED:"; cat $OUT/e2e-draft.json; exit 1; }
echo "draftRevision=$DRAFT_REVISION"
curl -s -X POST $BASE/admin/config/validate -H 'Content-Type: application/json' \
  -d "{\"draft_revision\":$DRAFT_REVISION}" -o $OUT/e2e-validate.json
VALIDATION_ID=$(json_field $OUT/e2e-validate.json data.validation_id)
[ -z "$VALIDATION_ID" ] && { echo "VALIDATE FAILED:"; cat $OUT/e2e-validate.json; exit 1; }
echo "validation=$VALIDATION_ID"
curl -s -X POST $BASE/admin/config/publish -H 'Content-Type: application/json' \
  -d "{\"validation_id\":\"$VALIDATION_ID\",\"draft_revision\":$DRAFT_REVISION,\"acknowledged_warning_ids\":$(python -c "
import json
d=json.load(open(r'$OUT/e2e-validate.json',encoding='utf-8'))
issues=d.get('data',{}).get('issues',[]) or []
ids=[i.get('code') for i in issues if (i.get('severity') or '').upper()=='WARNING']
print(json.dumps([i for i in ids if i]))
"),\"publish_note\":\"e2e\"}" \
  -o $OUT/e2e-publish.json
head -c 300 $OUT/e2e-publish.json; echo

echo "== 9. 实例准备→激活（驱动发布收敛） =="
NOW_ISO=$(python -c "from datetime import datetime,timezone; print(datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%S+00:00'))")
for i in 1 2 3 4 5 6; do
  HBEAT > $OUT/e2e-hb.json
  PREPARE=$(json_field $OUT/e2e-hb.json data.prepare_command.publish_id)
  ACTIVATE=$(json_field $OUT/e2e-hb.json data.activation_command.publish_id)
  PUBLISH_ID=$(json_field $OUT/e2e-publish.json data.publish_id)
  SNAP_NO=$(json_field $OUT/e2e-hb.json data.active_snapshot_no)
  TARGET_SNAP=$(json_field $OUT/e2e-hb.json data.prepare_command.snapshot_no)
  [ -z "$TARGET_SNAP" ] && TARGET_SNAP=$(json_field $OUT/e2e-hb.json data.activation_command.snapshot_no)
  [ -z "$TARGET_SNAP" ] && TARGET_SNAP=$SNAP_NO
  echo "loop $i: prepare=$PREPARE activate=$ACTIVATE target_snap=$TARGET_SNAP active_snap=$SNAP_NO"
  if [ -n "$PREPARE" ] && [ "$PREPARE" != "None" ]; then
    echo "prepare command for publish=$PREPARE snapshot=$SNAP_NO → 报告 READY"
    curl -s -X POST $BASE/internal/publish-records/$PREPARE/instances/$INSTANCE_ID/reports \
      -H 'Content-Type: application/json' -H "X-Light-AI-Instance-Token: $INTERNAL_TOKEN" -H "X-Light-AI-Instance-Id: $INSTANCE_ID" \
      -d '{"target_snapshot_no":'"$TARGET_SNAP"',"status":"READY","reported_at":null,"retry_count":0,"load_duration_ms":10,"error_code":null,"error_summary":null}' -o $OUT/e2e-report.json
    echo "report=$(head -c 200 $OUT/e2e-report.json)"
  elif [ -n "$ACTIVATE" ] && [ "$ACTIVATE" != "None" ]; then
    echo "activation command → 报告 LOADED"
    curl -s -X POST $BASE/internal/publish-records/$ACTIVATE/instances/$INSTANCE_ID/reports \
      -H 'Content-Type: application/json' -H "X-Light-AI-Instance-Token: $INTERNAL_TOKEN" -H "X-Light-AI-Instance-Id: $INSTANCE_ID" \
      -d '{"target_snapshot_no":'"$TARGET_SNAP"',"status":"LOADED","reported_at":null,"retry_count":0,"load_duration_ms":10,"error_code":null,"error_summary":null}' -o $OUT/e2e-report.json
    echo "report=$(head -c 200 $OUT/e2e-report.json)"
    HBEAT > $OUT/e2e-hb.json
    STATUS=$(python -c "
import json
d=json.load(open(r'$OUT/e2e-publish.json',encoding='utf-8'))
print(d.get('data',{}).get('status',''))")
    break
  else
    echo "no command; activeSnapshot=$SNAP_NO"
  fi
  sleep 1
done
curl -s "$BASE/admin/config/publish-records" -o $OUT/e2e-records.json
python -c "
import json
d=json.load(open(r'$OUT/e2e-records.json',encoding='utf-8'))
items=d.get('data',{}).get('items',[])
print('publish records:', [(i.get('status'), i.get('target_snapshot_no') or i.get('targetSnapshotNo')) for i in items][:3])
"

echo "== 10. 就绪检查 =="
curl -s $BASE/health/ready -o $OUT/e2e-ready.json -w "ready_http=%{http_code}\n"
cat $OUT/e2e-ready.json; echo

echo "== 11. 创建业务 Access Token =="
curl -s -X POST $BASE/admin/access-credentials -H 'Content-Type: application/json' \
  -d "{\"name\":\"e2e-token\",\"application\":\"e2e-app\",\"allowed_alias_ids\":[\"$ALIAS_ID\"],\"ip_allowlist\":[],\"enabled\":true}" \
  -o $OUT/e2e-token.json
BEARER=$(json_field $OUT/e2e-token.json data.token_value)
[ -z "$BEARER" ] && { echo "TOKEN FAILED:"; cat $OUT/e2e-token.json; exit 1; }
echo "token acquired (hidden)"

echo "== 12. /v1/models =="
curl -s $BASE/v1/models -H "Authorization: Bearer $BEARER" -o $OUT/e2e-models.json -w "models_http=%{http_code}\n"
python -c "
import json
d=json.load(open(r'$OUT/e2e-models.json',encoding='utf-8'))
print('models:', [m.get('id') for m in d.get('data',[])])
"

echo "== 13. /v1/chat/completions（走 Stub Provider 全链路） =="
curl -s -X POST $BASE/v1/chat/completions -H "Authorization: Bearer $BEARER" \
  -H 'Content-Type: application/json' \
  -d '{"model":"chat-demo","stream":false,"messages":[{"role":"user","content":"你好"}]}' \
  -o $OUT/e2e-chat.json -w "chat_http=%{http_code}\n"
python -c "
import json
d=json.load(open(r'$OUT/e2e-chat.json',encoding='utf-8'))
if 'error' in d: print('CHAT ERROR:', d['error'])
else:
    ch=d.get('choices',[{}])[0].get('message',{})
    print('content=', ch.get('content'))
    print('trace=', d.get('light_ai',{}).get('trace_id'), 'usage=', d.get('usage'))
"
echo "E2E DONE"
