package io.casehub.ras.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.casehub.platform.api.expression.CompiledExpression;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JqResultUnwrapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <R> JqResultUnwrapper<R> unwrapper(Object delegate, Class<R> resultType) {
        return new JqResultUnwrapper<>((CompiledExpression) delegate, resultType);
    }

    private static <C, R> CompiledExpression<C, R> expr(java.util.function.Function<C, R> fn) {
        return new CompiledExpression<>() {
            @Override
            public String type()     {return "jq";}

            @Override
            public R eval(C context) {return fn.apply(context);}
        };
    }

    @Test
    void unwrapsStringFromSingleElementList() {
        var delegate = expr((Map<String, Object> ctx) -> List.of(new TextNode("hello")));
        var wrapper  = unwrapper(delegate, String.class);
        assertThat(wrapper.eval(Map.of())).isEqualTo("hello");
    }

    @Test
    void unwrapsNullNodeToNull() {
        var delegate = expr((Map<String, Object> ctx) -> List.of(NullNode.getInstance()));
        var wrapper  = unwrapper(delegate, String.class);
        assertThat(wrapper.eval(Map.of())).isNull();
    }

    @Test
    void emptyListReturnsNull() {
        var delegate = expr((Map<String, Object> ctx) -> List.of());
        var wrapper  = unwrapper(delegate, String.class);
        assertThat(wrapper.eval(Map.of())).isNull();
    }

    @Test
    void unwrapsObjectViaConvertValue() {
        var    delegate = expr((Map<String, Object> ctx) -> List.of(MAPPER.valueToTree(Map.of("key", "val"))));
        var    wrapper  = unwrapper(delegate, Object.class);
        Object result   = wrapper.eval(Map.of());
        assertThat(result).isInstanceOf(Map.class);
    }

    @Test
    void typeReturnsJq() {
        var delegate = expr((Map<String, Object> ctx) -> List.of());
        var wrapper  = unwrapper(delegate, String.class);
        assertThat(wrapper.type()).isEqualTo("jq");
    }

    @Test
    void booleanResultPassesThroughWithoutUnwrapping() {
        var delegate = expr((Map<String, Object> ctx) -> true);
        var wrapper  = unwrapper(delegate, Boolean.class);
        assertThat(wrapper.eval(Map.of())).isEqualTo(true);
    }
}
