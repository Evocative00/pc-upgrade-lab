package com.pcupgradelab.catalog.shared;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/** Public product pages only. Source identifiers are parsed rather than matched by URL suffix. */
public final class SharedPriceSourcePolicy {
    private SharedPriceSourcePolicy() { }

    public static boolean accepts(String sourceName, String sourceUrl) {
        return productId(sourceName, sourceUrl) != null;
    }

    public static boolean matches(String sourceName, String sourceUrl, String externalId) {
        return externalId != null && externalId.equals(productId(sourceName, sourceUrl));
    }

    public static String productId(String sourceName, String sourceUrl) {
        if (sourceName == null || sourceUrl == null || sourceUrl.length() > 2048) return null;
        try {
            URI uri = URI.create(sourceUrl);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawFragment() != null || uri.isOpaque()) return null;
            if ("ICODA".equals(sourceName)) {
                if (!"usr.icoda.co.kr".equals(uri.getHost()) || uri.getRawQuery() != null
                        || !uri.getRawPath().matches("/item/view/[1-9][0-9]{0,15}")) return null;
                return uri.getRawPath().substring("/item/view/".length());
            }
            if ("COMPUZONE".equals(sourceName)) {
                if (!"www.compuzone.co.kr".equals(uri.getHost())
                        || !"/product/product_detail.htm".equals(uri.getRawPath())) return null;
                Map<String, String> query = numericQuery(uri.getRawQuery());
                if (query == null || !query.keySet().stream().allMatch(key ->
                        key.equals("ProductNo") || key.equals("DivNo") || key.equals("MediumDivNo"))) return null;
                String id = query.get("ProductNo");
                return id != null && id.matches("[1-9][0-9]{0,15}") ? id : null;
            }
            String query = uri.getRawQuery();
            if (query == null) return null;
            if ("DANAWA".equals(sourceName) && "prod.danawa.com".equals(uri.getHost())
                    && "/info/".equals(uri.getRawPath()) && query.matches("pcode=[0-9]{1,128}"))
                return query.substring("pcode=".length());
            if ("SAMSUNG_CNH".equals(sourceName) && "www.samsungzip.shop".equals(uri.getHost())
                    && "/goods/goods_view.php".equals(uri.getRawPath()) && query.matches("goodsNo=[0-9]{1,128}"))
                return query.substring("goodsNo=".length());
            return null;
        } catch (IllegalArgumentException ex) { return null; }
    }

    private static Map<String, String> numericQuery(String value) {
        if (value == null || value.isBlank()) return null;
        var result = new HashMap<String, String>();
        for (String entry : value.split("&", -1)) {
            String[] pair = entry.split("=", -1);
            if (pair.length != 2 || !pair[1].matches("[0-9]{1,16}")
                    || result.putIfAbsent(pair[0], pair[1]) != null) return null;
        }
        return result;
    }
}
