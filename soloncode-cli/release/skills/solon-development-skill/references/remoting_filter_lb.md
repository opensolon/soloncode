# Remoting Filter / LB — Nami 过滤器 / 发现 / 负载均衡

> 适用场景：Nami 过滤器、本地/分布式发现、LoadBalance 定制。
>
> 目标版本：4.0.3。RPC 基础 / 注解 / 声明式 HttpClient 见 `remoting.md`。Socket.D 见 `socketd.md`。

## Nami 过滤器

Nami 过滤器有两种作用域：自身过滤器（仅对当前接口有效）和全局过滤器（对所有接口有效）。

### 自身过滤器

为当前接口添加专属过滤器。在声明式 HttpClient 体验中，方便为不同站点指定编码等过滤策略。

```java
@NamiClient(url = "http://localhost:8080/ComplexModelService/")
public interface IComplexModelService extends Filter {
    @NamiMapping("PUT")
    void save(@NamiBody ComplexModel model);

    @NamiMapping("GET api/1.0.1")
    ComplexModel read(Integer modelId);

    // 自带过滤器，要用 default 直接实现
    default Result doFilter(Invocation inv) throws Throwable {
        inv.headers.put("Token", "Xxx");
        inv.headers.put("TraceId", Utils.guid());
        inv.config.setDecoder(Snack4Decoder.instance);
        inv.config.setEncoder(Snack4Encoder.instance);
        return inv.invoke();
    }
}
```

### 全局过滤器

使用组件注解：

```java
@Component
public class NamiFilterImpl implements org.noear.nami.Filter {
    @Override
    public Result doFilter(Invocation inv) throws Throwable {
        inv.headers.put("Token", "Xxx");
        inv.headers.put("TraceId", Utils.guid());
        inv.config.setDecoder(Snack4Decoder.instance);
        inv.config.setEncoder(Snack4Encoder.instance);
        return inv.invoke();
    }
}
```

或使用手动注册（要注意时机，在 Nami 使用前完成注册）：

```java
NamiManager.reg(inv -> {
    inv.headers.put("Token", "Xxx");
    inv.headers.put("TraceId", Utils.guid());
    inv.config.setDecoder(Snack4Decoder.instance);
    inv.config.setEncoder(Snack4Encoder.instance);
    return inv.invoke();
});
```

> snack4 import：`org.noear.nami.coder.snack4.Snack4Decoder` / `Snack4Encoder`。  
> 若仍使用 snack3，类名为 `SnackDecoder` / `SnackEncoder`（`nami-coder-snack3`），勿与 snack4 混用。

## 注册与发现服务

### 本地发现服务

引入 `solon-cloud` 插件依赖（自带了本地发现能力）。

服务端不需要改造，也不需要注册。

客户端配置：

```yaml
solon.cloud.local:
  discovery:
    service:
      userapi: # 添加本地服务发现（userapi 为服务名）
        - "http://localhost:8081"
```

客户端代码：

```java
@Mapping("user")
public class UserController {
    // 指定服务名、路径和序列化方案（不用关注服务地址）
    @NamiClient(name = "userapi", path = "/rpc/v1/user", headers = ContentTypes.JSON)
    UserService userService;
}
```

### 分布式注册与发现服务

使用 Solon Cloud Discovery 相关组件（如 nacos、zookeeper、water 等）。插件对照见 `cloud_core.md`。

服务端配置（以 water 为例）：

```yaml
server.port: 9001

solon.app:
  group: "demo"
  name: "userapi"

solon.cloud.water:
  server: "waterapi:9371"
```

客户端配置：

```yaml
server.port: 8081

solon.app:
  group: "demo"
  name: "userdemo"

solon.cloud.water:
  server: "waterapi:9371"
```

客户端代码与本地发现方式一致，都使用 `name` 而非 `url` 引用服务。

## LoadBalance — 负载均衡

内核接口，nami 和 httputils 都使用它进行服务调用：

```java
// 根据服务名获取"负载均衡"
LoadBalance loadBalance = LoadBalance.get("serviceName");

// 根据分组和服务名获取"负载均衡"
LoadBalance loadBalance = LoadBalance.get("groupName", "serviceName");

// 获取服务实例地址（例："http://12.0.1.2.3:8871"）
String server = loadBalance.getServer();
```

默认实现：`CloudLoadBalanceFactory`（基于 Solon Cloud Discovery）。引入 Solon Cloud Discovery 相关的组件即可使用。

### 策略定制

```java
@Configuration
public class Config {
    @Bean
    public CloudLoadStrategy loadStrategy() {
        return new CloudLoadStrategyDefault(); // 默认轮询
        // return new CloudLoadStrategyIpHash(); // IP 哈希
    }
}
```

自定义策略示例（如基于 k8s 服务地址）：

```java
@Component
public class CloudLoadStrategyImpl implements CloudLoadStrategy {
    @Override
    public String getServer(Discovery discovery) {
        // 通过服务名，获取 k8s 的服务地址
        return K8sUtil.getServer(discovery.service());
    }
}
```

### 自定义负载均衡实现

基于内核接口 `LoadBalance.Factory` 实现：

```java
@Component
public class LoadBalanceFactoryImpl implements LoadBalance.Factory {
    @Override
    public LoadBalance create(String group, String service) {
        if ("local".equals(service)) {
            return new LoadBalanceImpl();
        }
        return null;
    }
}
```
