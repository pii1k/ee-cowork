package io.autocrypt.jwlee.ragworkaround;

import com.embabel.agent.api.reference.LlmReference;
import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.rag.service.SearchOperations;
import com.embabel.agent.rag.tools.ToolishRag;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.stream.Collectors;

public class JsonSafeToolishRag implements LlmReference {

    private final ToolishRag delegate;

    public JsonSafeToolishRag(String name, String description, SearchOperations searchOperations) {
        this.delegate = new ToolishRag(name, description, searchOperations);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public String notes() {
        return delegate.notes();
    }

    @Override
    public String contribution() {
        return delegate.contribution();
    }

    @Override
    public List<Tool> tools() {
        return delegate.tools().stream().map(this::wrapTool).collect(Collectors.toList());
    }

    private Tool wrapTool(Tool originalTool) {
        return (Tool) Proxy.newProxyInstance(
                Tool.class.getClassLoader(),
                new Class<?>[]{Tool.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("call".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof String) {
                            Tool.Result originalResult = (Tool.Result) method.invoke(originalTool, args);
                            return wrapResult(originalResult);
                        }
                        return method.invoke(originalTool, args);
                    }
                }
        );
    }

    private Tool.Result wrapResult(Tool.Result originalResult) {
        try {
            Method getContent = originalResult.getClass().getMethod("getContent");
            String content = (String) getContent.invoke(originalResult);
            return Tool.Result.text(wrapInJson(content));
        } catch (Exception e) {
            String str = originalResult.toString();
            String content = str;
            if (str.startsWith("TextResult(content=")) {
                content = str.substring("TextResult(content=".length(), str.length() - 1);
            } else if (str.startsWith("Success(content=")) {
                content = str.substring("Success(content=".length(), str.length() - 1);
            }
            return Tool.Result.text(wrapInJson(content));
        }
    }

    private String wrapInJson(String content) {
        if (content == null) content = "";
        String escaped = content.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "");
        return "{\"result\": \"" + escaped + "\"}";
    }
}
