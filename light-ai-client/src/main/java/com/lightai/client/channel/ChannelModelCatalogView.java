package com.lightai.client.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChannelModelCatalogView(
        List<ChannelModelCatalogItem> items,
        String nextCursor,
        boolean manualInputAllowed) {

    public ChannelModelCatalogView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
