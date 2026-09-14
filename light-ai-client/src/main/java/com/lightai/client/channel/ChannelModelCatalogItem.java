package com.lightai.client.channel;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChannelModelCatalogItem(
        String id,
        String channelId,
        String modelName,
        String displayName,
        boolean active) {
}
