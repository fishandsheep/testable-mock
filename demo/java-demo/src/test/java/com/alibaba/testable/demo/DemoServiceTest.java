package com.alibaba.testable.demo;

import com.alibaba.testable.core.accessor.PrivateAccessor;
import com.alibaba.testable.core.annotation.EnablePrivateAccess;
import com.alibaba.testable.core.annotation.TestableMock;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static com.alibaba.testable.core.tool.TestableTool.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnablePrivateAccess
class DemoServiceTest {

    @TestableMock
    private BlackBox createBlackBox(String text) {
        return new BlackBox("mock_" + text);
    }

    @TestableMock
    private String innerFunc(String text) {
        return "mock_" + text;
    }

    @TestableMock(targetClass = String.class)
    private String trim(String self) {
        return "trim_string";
    }

    @TestableMock(targetClass = String.class, targetMethod = "substring")
    private String sub(String self, int i, int j) {
        return "sub_string";
    }

    @TestableMock(targetClass = String.class)
    private boolean startsWith(String self, String s) {
        return false;
    }

    @TestableMock
    private String callFromDifferentMethod() {
        if (TEST_CASE.equals("should_able_to_get_test_case_name")) {
            return "mock_special";
        }
        switch (SOURCE_METHOD) {
            case "callerOne": return "mock_one";
            default: return "mock_others";
        }
    }

    private DemoService demoService = new DemoService();

    @Test
    void should_able_to_test_private_method() throws Exception {
        assertEquals("hello - 1", demoService.privateFunc("hello", 1));
        assertEquals("hello - 1", PrivateAccessor.invoke(demoService, "privateFunc", "hello", 1));
    }

    @Test
    void should_able_to_test_private_field() throws Exception {
        demoService.count = 2;
        assertEquals("4", demoService.privateFieldAccessFunc());
        PrivateAccessor.set(demoService, "count", 3);
        assertEquals("5", demoService.privateFieldAccessFunc());
        assertEquals(new Integer(5), PrivateAccessor.get(demoService, "count"));
    }

    @Test
    void should_able_to_test_new_object() throws Exception {
        assertEquals("mock_something", demoService.newFunc());
        verify("createBlackBox").times(1);
    }

    @Test
    void should_able_to_test_member_method() throws Exception {
        assertEquals("{ \"res\": \"mock_hello\"}", demoService.outerFunc("hello"));
        verify("innerFunc").times(1);
    }

    @Test
    void should_able_to_test_common_method() throws Exception {
        assertEquals("trim_string__sub_string__false", demoService.commonFunc());
        verify("trim").times(1);
        verify("sub").times(1);
        verify("startsWith").times(1);
    }

    @Test
    void should_able_to_get_source_method_name() throws Exception {
        // synchronous
        assertEquals("mock_one_mock_others", demoService.callerOne() + "_" + demoService.callerTwo());
        // asynchronous
        assertEquals("mock_one_mock_others",
            Executors.newSingleThreadExecutor().submit(() -> demoService.callerOne() + "_" + demoService.callerTwo()).get());
        verify("callFromDifferentMethod").times(4);
    }

    @Test
    void should_able_to_get_test_case_name() throws Exception {
        // synchronous
        assertEquals("mock_special", demoService.callerOne());
        // asynchronous
        assertEquals("mock_special", Executors.newSingleThreadExecutor().submit(() -> demoService.callerOne()).get());
        verify("callFromDifferentMethod").times(2);
    }

}