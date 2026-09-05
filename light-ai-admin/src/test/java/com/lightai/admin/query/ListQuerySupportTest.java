package com.lightai.admin.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** BE-004 列表查询基座：分页边界与排序白名单。 */
class ListQuerySupportTest {

    private static final Set<String> COLUMNS = Set.of("updated_at", "created_at", "name");

    @Test
    void defaultsArePageOneSizeTwentyAndDefaultSort() {
        ListQuerySupport.ListQuery query =
                ListQuerySupport.parse(null, null, null, COLUMNS, "updated_at desc");

        assertThat(query.page()).isEqualTo(1);
        assertThat(query.pageSize()).isEqualTo(20);
        assertThat(query.sort()).isEqualTo("updated_at desc");
        assertThat(query.offset()).isZero();
        assertThat(query.limit()).isEqualTo(20);
    }

    @Test
    void explicitValuesAreNormalized() {
        ListQuerySupport.ListQuery query =
                ListQuerySupport.parse("3", "50", "NAME ASC", COLUMNS, "updated_at desc");

        assertThat(query.page()).isEqualTo(3);
        assertThat(query.pageSize()).isEqualTo(50);
        assertThat(query.sort()).isEqualTo("name asc");
        assertThat(query.offset()).isEqualTo(100);
    }

    @Test
    void pageSizeBeyondHundredIsRejected() {
        assertThatThrownBy(() -> ListQuerySupport.parse("1", "101", null, COLUMNS, "updated_at desc"))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
    }

    @Test
    void pageZeroAndNonNumericInputsAreRejected() {
        assertThatThrownBy(() -> ListQuerySupport.parse("0", null, null, COLUMNS, "updated_at desc"))
                .isInstanceOf(LightAiException.class);

        assertThatThrownBy(() -> ListQuerySupport.parse("abc", "x", null, COLUMNS, "updated_at desc"))
                .isInstanceOf(LightAiException.class);
    }

    @Test
    void sortColumnOutsideWhitelistIsRejected() {
        assertThatThrownBy(() ->
                ListQuerySupport.parse(null, null, "version; drop table x", COLUMNS, "updated_at desc"))
                .isInstanceOf(LightAiException.class)
                .hasMessageContaining("列表查询参数不合法");
    }

    @Test
    void invalidSortDirectionIsRejected() {
        assertThatThrownBy(() -> ListQuerySupport.parse(null, null, "updated_at sideways", COLUMNS, null))
                .isInstanceOf(LightAiException.class);
    }

    @Test
    void missingDefaultSortIsRejected() {
        assertThatThrownBy(() -> ListQuerySupport.parse(null, null, null, COLUMNS, null))
                .isInstanceOf(LightAiException.class);
    }
}
