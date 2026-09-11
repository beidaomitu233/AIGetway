#!/usr/bin/env python3
# Mechanical rename for resource-domain refactor (V1 -> V2).
# Applies token-level replacements and moves/renames/deletes files.
import os, re, sys

REPO = os.path.dirname(os.path.abspath(__file__))

INCLUDED_ROOTS = [
    "light-ai-storage-jdbc",
    "light-ai-admin",
    "light-ai-client",
    "light-ai-runtime",
    "light-ai-server",
    "light-ai-spring-boot-starter",
    "light-ai-storage-redis",
]

EXCLUDE_PREFIXES = [
    "light-ai-spi/",
    "light-ai-provider-common/",
    "light-ai-provider-openai/",
    "light-ai-provider-anthropic/",
    "light-ai-provider-gemini/",
    "light-ai-provider-deepseek/",
    "light-ai-admin-ui/",
    # schema package already migrated (do not touch)
    "light-ai-storage-jdbc/src/main/java/com/lightai/storage/schema/",
    "light-ai-storage-jdbc/src/test/java/com/lightai/storage/schema/",
    # snapshot ENTITIES rewritten by hand
    "light-ai-storage-jdbc/src/main/java/com/lightai/storage/publish/JdbcSnapshotContentRepository.java",
    # access domain is a separate concept (end-user API keys)
    "light-ai-storage-jdbc/src/main/java/com/lightai/storage/access/",
    "light-ai-storage-jdbc/src/test/java/com/lightai/storage/access/",
    "light-ai-admin/src/main/java/com/lightai/admin/accesscred/",
    "light-ai-admin/src/test/java/com/lightai/admin/accesscred/",
]

DELETE_FILES = {
    os.path.join(REPO, "light-ai-storage-jdbc/src/main/java/com/lightai/storage/credential/JdbcCredentialSecretPort.java"),
    os.path.join(REPO, "light-ai-storage-jdbc/src/main/java/com/lightai/storage/credential/JdbcCredentialSecretRepository.java"),
    os.path.join(REPO, "light-ai-storage-jdbc/src/main/java/com/lightai/storage/credential/SecretRecordRow.java"),
    os.path.join(REPO, "light-ai-storage-jdbc/src/main/java/com/lightai/storage/pool/PoolRecord.java"),
    os.path.join(REPO, "light-ai-storage-jdbc/src/main/java/com/lightai/storage/pool/JdbcPoolRepository.java"),
    os.path.join(REPO, "light-ai-admin/src/main/java/com/lightai/admin/pool/PoolController.java"),
    os.path.join(REPO, "light-ai-admin/src/main/java/com/lightai/admin/pool/PoolService.java"),
    os.path.join(REPO, "light-ai-client/src/main/java/com/lightai/client/pool/CredentialPoolDetail.java"),
    os.path.join(REPO, "light-ai-client/src/main/java/com/lightai/client/pool/CredentialPoolListItem.java"),
    os.path.join(REPO, "light-ai-client/src/main/java/com/lightai/client/pool/PoolSaveCommand.java"),
}

PKG_MAP = {
    "com/lightai/storage/provider": "com/lightai/storage/channel",
    "com/lightai/storage/credential": "com/lightai/storage/channel",
    "com/lightai/storage/model": "com/lightai/storage/upstream",
    "com/lightai/admin/provider": "com/lightai/admin/channel",
    "com/lightai/admin/credential": "com/lightai/admin/channel",
    "com/lightai/admin/model": "com/lightai/admin/upstream",
    "com/lightai/client/provider": "com/lightai/client/channel",
    "com/lightai/client/credential": "com/lightai/client/channel",
    "com/lightai/client/pool": "com/lightai/client/channel",
    "com/lightai/client/model": "com/lightai/client/upstream",
}

# Class / identifier renames (case-sensitive, longest-specific first)
CLASS_MAP = [
    ("ProviderModelRecord", "UpstreamModelRecord"),
    ("ProviderModelService", "UpstreamModelService"),
    ("ProviderModelController", "UpstreamModelController"),
    ("ProviderModelDetail", "UpstreamModelDetail"),
    ("ProviderModelSaveCommand", "UpstreamModelSaveCommand"),
    ("ProviderModelImportCommand", "UpstreamModelImportCommand"),
    ("JdbcProviderModelRepository", "JdbcUpstreamModelRepository"),
    ("ProviderCheckRecord", "ChannelCheckRecord"),
    ("ProviderCheckService", "ChannelCheckService"),
    ("ProviderCheckCommand", "ChannelCheckCommand"),
    ("JdbcProviderCheckRecordRepository", "JdbcChannelCheckRecordRepository"),
    ("ProviderRecord", "ChannelRecord"),
    ("JdbcProviderRepository", "JdbcChannelRepository"),
    ("ProviderService", "ChannelService"),
    ("ProviderController", "ChannelController"),
    ("ProviderDetail", "ChannelDetail"),
    ("ProviderListItem", "ChannelListItem"),
    ("ProviderSaveCommand", "ChannelSaveCommand"),
    ("CredentialCreateCommand", "ChannelCredentialCreateCommand"),
    ("CredentialUpdateCommand", "ChannelCredentialUpdateCommand"),
    ("CredentialRotateCommand", "ChannelCredentialRotateCommand"),
    ("CredentialRecord", "ChannelCredentialRecord"),
    ("JdbcCredentialRepository", "JdbcChannelCredentialRepository"),
    ("CredentialService", "ChannelCredentialService"),
    ("CredentialController", "ChannelCredentialController"),
    ("CredentialDetail", "ChannelCredentialDetail"),
    ("CredentialListItem", "ChannelCredentialListItem"),
]

CAMEL_MAP = [
    ("credentialPoolId", "channelId"),
    ("providerModelId", "upstreamModelId"),
    ("finalCredentialId", "finalChannelCredentialId"),
    ("credentialId", "channelCredentialId"),
    ("finalProviderId", "finalChannelId"),
    ("providerId", "channelId"),
    ("poolId", "channelId"),
    ("providerModelNameSnapshot", "upstreamModelNameSnapshot"),
    ("providerModelName", "upstreamModelName"),
    ("providerNameSnapshot", "channelNameSnapshot"),
    ("providerName", "channelName"),
    ("providerStartedAt", "channelStartedAt"),
    ("providerRequestId", "channelRequestId"),
    ("credentialNameSnapshot", "channelCredentialNameSnapshot"),
    ("finalCredentialName", "finalChannelCredentialName"),
    ("finalProviderName", "finalChannelName"),
    ("finalProviderModelName", "finalUpstreamModelName"),
    ("finalProviderModelId", "finalUpstreamModelId"),
]

SNAKE_MAP = [
    ("credential_pool_id", "channel_id"),
    ("provider_model_name_snapshot", "upstream_model_name_snapshot"),
    ("credential_name_snapshot", "channel_credential_name_snapshot"),
    ("provider_name_snapshot", "channel_name_snapshot"),
    ("provider_model_id", "upstream_model_id"),
    ("provider_check_record", "channel_check_record"),
    ("provider_request_id", "channel_request_id"),
    ("provider_model", "upstream_model"),
    ("provider_name", "channel_name"),
    ("provider_started_at", "channel_started_at"),
    ("pool_id", "channel_id"),
    ("provider_id", "channel_id"),
    ("credential_id", "channel_credential_id"),
]

QUOTED_MAP = [
    ('"provider_model"', '"upstream_model"'),
    ('"provider_check_record"', '"channel_check_record"'),
    ('"credential"', '"channel_credential"'),
    ('"provider"', '"channel"'),
]

ENTITY_MAP = [
    ("'PROVIDER_MODEL'", "'UPSTREAM_MODEL'"),
    ("'PROVIDER'", "'CHANNEL'"),
    ("'CREDENTIAL'", "'CHANNEL_CREDENTIAL'"),
]

PKG_REPLACE = [
    ("com.lightai.storage.provider", "com.lightai.storage.channel"),
    ("com.lightai.storage.credential", "com.lightai.storage.channel"),
    ("com.lightai.storage.model", "com.lightai.storage.upstream"),
    ("com.lightai.admin.provider", "com.lightai.admin.channel"),
    ("com.lightai.admin.credential", "com.lightai.admin.channel"),
    ("com.lightai.admin.model", "com.lightai.admin.upstream"),
    ("com.lightai.client.provider", "com.lightai.client.channel"),
    ("com.lightai.client.credential", "com.lightai.client.channel"),
    ("com.lightai.client.pool", "com.lightai.client.channel"),
    ("com.lightai.client.model", "com.lightai.client.upstream"),
]

P1 = "\x01AC1\x01"  # protects access_credential_id
P2 = "\x01AC2\x01"  # protects accessCredentialId

def apply_all(text):
    # protect access domain
    text = text.replace("access_credential_id", P1)
    text = text.replace("accessCredentialId", P2)
    for old, new in SNAKE_MAP:
        text = text.replace(old, new)
    for old, new in CAMEL_MAP:
        text = text.replace(old, new)
    for old, new in QUOTED_MAP:
        text = text.replace(old, new)
    for old, new in ENTITY_MAP:
        text = text.replace(old, new)
    for old, new in PKG_REPLACE:
        text = text.replace(old, new)
    for old, new in CLASS_MAP:
        text = text.replace(old, new)
    # restore access domain
    text = text.replace(P1, "access_credential_id")
    text = text.replace(P2, "accessCredentialId")
    return text

def apply_class_to_name(name):
    for old, new in CLASS_MAP:
        name = name.replace(old, new)
    return name

def new_path_for(root, relpath):
    m = re.search(r'src/(main|test)/java/(.+)$', relpath.replace(os.sep, "/"))
    if not m:
        return None
    srctype = m.group(1)
    pkgrel = m.group(2)  # com/lightai/.../Name.java
    for oldpkg, newpkg in PKG_MAP.items():
        if pkgrel.startswith(oldpkg + "/"):
            pkgrel = newpkg + pkgrel[len(oldpkg):]
            break
    d = os.path.dirname(pkgrel)
    base = os.path.basename(pkgrel)
    newbase = apply_class_to_name(base)
    return os.path.join(root, "src", srctype, "java", d, newbase)

def main():
    moved, deleted, changed, skipped = 0, 0, 0, 0
    for root in INCLUDED_ROOTS:
        rootdir = os.path.join(REPO, root)
        if not os.path.isdir(rootdir):
            continue
        for dirpath, dirnames, filenames in os.walk(rootdir):
            for fn in filenames:
                if not fn.endswith(".java"):
                    continue
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, REPO).replace(os.sep, "/")
                excl = False
                for pref in EXCLUDE_PREFIXES:
                    if rel.startswith(pref) or ("/" + pref) in ("/" + rel):
                        excl = True
                        break
                if excl:
                    skipped += 1
                    continue
                if os.path.abspath(full) in DELETE_FILES:
                    os.remove(full)
                    deleted += 1
                    continue
                text = open(full, "r", encoding="utf-8").read()
                newtext = apply_all(text)
                newfull = new_path_for(root, rel)
                if newfull is None:
                    # not under src/java; leave in place but still replace
                    if newtext != text:
                        open(full, "w", encoding="utf-8").write(newtext)
                        changed += 1
                    continue
                if newfull != full:
                    os.makedirs(os.path.dirname(newfull), exist_ok=True)
                    # avoid clobber
                    if os.path.exists(newfull) and os.path.abspath(newfull) != os.path.abspath(full):
                        print("COLLISION:", newfull)
                    open(newfull, "w", encoding="utf-8").write(newtext)
                    if os.path.abspath(newfull) != os.path.abspath(full):
                        os.remove(full)
                    moved += 1
                else:
                    if newtext != text:
                        open(full, "w", encoding="utf-8").write(newtext)
                        changed += 1
    print(f"moved={moved} deleted={deleted} changed_inplace={changed} skipped={skipped}")
    # remove now-empty dirs
    for d in [
        os.path.join(REPO, "light-ai-storage-jdbc/src/main/java/com/lightai/storage/pool"),
        os.path.join(REPO, "light-ai-storage-jdbc/src/test/java/com/lightai/storage/pool"),
        os.path.join(REPO, "light-ai-admin/src/main/java/com/lightai/admin/pool"),
        os.path.join(REPO, "light-ai-admin/src/test/java/com/lightai/admin/pool"),
        os.path.join(REPO, "light-ai-client/src/main/java/com/lightai/client/pool"),
        os.path.join(REPO, "light-ai-client/src/test/java/com/lightai/client/pool"),
    ]:
        try:
            if os.path.isdir(d) and not os.listdir(d):
                os.rmdir(d)
                print("rmdir empty:", d)
        except Exception as e:
            print("rmdir failed:", d, e)

if __name__ == "__main__":
    main()
