package com.tianji.aigc.agent;

import cn.hutool.core.map.MapUtil;
import com.alibaba.dashscope.app.Application;
import com.alibaba.dashscope.app.ApplicationParam;
import com.alibaba.dashscope.app.ApplicationResult;
import com.alibaba.dashscope.utils.JsonUtils;
import io.reactivex.Flowable;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class AppTest {

    @Test

    public void testAppCall() throws Exception {
        // 构造业务参数
        String token = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJ1c2VyIjp7InVzZXJJZCI6Miwicm9sZUlkIjoyLCJyZW1lbWJlck1lIjpmYWxzZX0sImV4cCI6MTc3ODA0MTQ4M30.YhbPTLIakajSD4Ni1UDYF8AHOoM1PTe5aafBKL7BzSZo5Fo7VxlBzHelf0iPTduV1DwBjL5rM_mg4oB3_DeN4W2ZFNM1zMi5il0LjnUylZcXsVtBX6SErsDRf4rXDv2UoFatcnWFfxGOGYKzd4t9tbRjTHTWqyj9gsp4fuCFjXd42AjB29oLUZKHdeJxb-oImhhlKLOToGzoiGpQNxydEbgb6USqH1LDS46E1CDPORYyAr0iSIwyMLnl2YtApXOiR50C1X5PcFW5mbzPfxGN0FkaUpk2509S9RJfCvuBU3LbQK4laVOk7iZ6bG9zlJ8JZR8DWEql51xcyWHYjLSJNw";
        Map<String, Object> bizParams = MapUtil.<String, Object>builder()
                .put("user_defined_tokens", MapUtil.of("tool_72b81df8-202e-460b-94f9-aab38b9177af", // 工具id
                        MapUtil.of("user_token", token)))
                .build();

        // bizParams.add("user_defined_tokens", JsonObject);
        ApplicationParam param = ApplicationParam.builder()
                // 若没有配置环境变量，可用百炼API Key将下行替换为：.apiKey("sk-xxx")。但不建议在生产环境中直接将API Key硬编码到代码中，以减少API Key泄露风险。
                .apiKey("sk-6e2df88f7c934b5896c96604bf4b4c13")
                .appId("64ffe20341ed422c8a0f907bb871000c") // 智能体id
                .prompt("查询课程，id为：2")
                .incrementalOutput(true) // 开启增量输出
                .bizParams(JsonUtils.toJsonObject(bizParams))
                .build();

        Application application = new Application();
        Flowable<ApplicationResult> result = application.streamCall(param);

        // 阻塞式的打印内容
        result.blockingForEach(data -> {
            System.out.printf("%s\n",data.getOutput().getText());
        });

    }

}
