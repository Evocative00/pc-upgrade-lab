package com.pcupgradelab.catalog.shared;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharedPriceSourcePolicyTests {
    private record Page(String source, String url, String id) { }

    @Test
    void supportedPublicPagesParseTheirOwnProductIdWithoutDependingOnQueryOrder() {
        for (var page : List.of(
                new Page("DANAWA", "https://prod.danawa.com/info/?pcode=74255378", "74255378"),
                new Page("SAMSUNG_CNH", "https://www.samsungzip.shop/goods/goods_view.php?goodsNo=1000000066", "1000000066"),
                new Page("ICODA", "https://usr.icoda.co.kr/item/view/1739432", "1739432"),
                new Page("COMPUZONE", "https://www.compuzone.co.kr/product/product_detail.htm?ProductNo=1183680", "1183680"),
                new Page("COMPUZONE", "https://www.compuzone.co.kr/product/product_detail.htm?DivNo=0&MediumDivNo=1012&ProductNo=1183680", "1183680"),
                new Page("COMPUZONE", "https://www.compuzone.co.kr/product/product_detail.htm?ProductNo=1183680&MediumDivNo=1012&DivNo=0", "1183680"))) {
            assertThat(SharedPriceSourcePolicy.accepts(page.source(), page.url())).as(page.url()).isTrue();
            assertThat(SharedPriceSourcePolicy.productId(page.source(), page.url())).isEqualTo(page.id());
            assertThat(SharedPriceSourcePolicy.matches(page.source(), page.url(), page.id())).isTrue();
            assertThat(SharedPriceSourcePolicy.matches(page.source(), page.url(), page.id() + "0")).isFalse();
            assertThat(SharedPriceSourcePolicy.matches(page.source(), page.url(), " " + page.id())).isFalse();
        }
    }

    @Test
    void credentialsExplicitPortsFragmentsForeignHostsAndNonHttpsCannotBePriceEvidence() {
        for (String url : List.of(
                "http://prod.danawa.com/info/?pcode=74255378",
                "https://prod.danawa.com:443/info/?pcode=74255378",
                "https://prod.danawa.com:444/info/?pcode=74255378",
                "https://reader:dummy@prod.danawa.com/info/?pcode=74255378",
                "https://prod.danawa.com/info/?pcode=74255378#price",
                "https://prod.danawa.com.example.org/info/?pcode=74255378",
                "https://example.org/info/?pcode=74255378",
                "https://prod.danawa.com/info/?pcode=74255378&token=dummy")) {
            assertThat(SharedPriceSourcePolicy.accepts("DANAWA", url)).as(url).isFalse();
            assertThat(SharedPriceSourcePolicy.productId("DANAWA", url)).isNull();
        }
        assertThat(SharedPriceSourcePolicy.accepts("ICODA", "https://usr.icoda.co.kr:443/item/view/1739432")).isFalse();
        assertThat(SharedPriceSourcePolicy.accepts("COMPUZONE",
                "https://www.compuzone.co.kr/product/product_detail.htm?ProductNo=1183680#price")).isFalse();
        assertThat(SharedPriceSourcePolicy.accepts("SAMSUNG_CNH",
                "https://reader@www.samsungzip.shop/goods/goods_view.php?goodsNo=1000000066")).isFalse();
    }

    @Test
    void productPagesRejectAmbiguousUnknownEncodedOrRepeatedQueryParameters() {
        for (String suffix : List.of("", "?ProductNo=", "?ProductNo=0", "?ProductNo=abc", "?DivNo=0",
                "?ProductNo=1183680&ProductNo=1183680", "?ProductNo=1183680&ProductNo=1183681",
                "?ProductNo=1183680&DivNo=0&DivNo=0", "?ProductNo=1183680&Currency=KRW",
                "?ProductNo=1183680&MediumDivNo=abc", "?ProductNo=1183680&", "?ProductNo=1183680=7",
                "?%50roductNo=1183680", "?ProductNo=%31%31%38%33%36%38%30", "?productno=1183680")) {
            assertThat(SharedPriceSourcePolicy.accepts("COMPUZONE",
                    "https://www.compuzone.co.kr/product/product_detail.htm" + suffix)).as(suffix).isFalse();
        }
        for (String suffix : List.of("", "?pcode=", "?pcode=abc", "?pcode=74255378&pcode=74255378",
                "?pcode=74255378&ProductNo=1", "?%70code=74255378")) {
            assertThat(SharedPriceSourcePolicy.accepts("DANAWA", "https://prod.danawa.com/info/" + suffix)).as(suffix).isFalse();
        }
        for (String path : List.of("/item/view/1739432/", "/item/view/01739432", "/item/view/1739432?ProductNo=1739432",
                "/item/view/%31%37%33%39%34%33%32", "/item/view/0")) {
            assertThat(SharedPriceSourcePolicy.accepts("ICODA", "https://usr.icoda.co.kr" + path)).as(path).isFalse();
        }
        assertThat(SharedPriceSourcePolicy.accepts("SAMSUNG_CNH",
                "https://www.samsungzip.shop/goods/goods_view.php?goodsNo=1000000066&coupon=1")).isFalse();
    }

    @Test
    void aProviderCodeCannotAuthorizeAnotherProvidersUrlOrAMissingIdentity() {
        String url = "https://usr.icoda.co.kr/item/view/1739432";
        for (String source : List.of("COMPUZONE", "DANAWA", "SAMSUNG_CNH", "OTHER", "icoda", ""))
            assertThat(SharedPriceSourcePolicy.accepts(source, url)).as(source).isFalse();
        assertThat(SharedPriceSourcePolicy.accepts(null, url)).isFalse();
        assertThat(SharedPriceSourcePolicy.accepts("ICODA", null)).isFalse();
        assertThat(SharedPriceSourcePolicy.accepts("ICODA", "")).isFalse();
        assertThat(SharedPriceSourcePolicy.matches("ICODA", url, null)).isFalse();
        assertThat(SharedPriceSourcePolicy.accepts("DANAWA", "https:opaque-product")).isFalse();
    }
}
