# Minimal Solon Web 模板

目标版本：**4.0.3**。

## 布局建议

```
src/main/java/com/example/demo/App.java
src/main/java/com/example/demo/controller/HelloController.java
src/main/resources/app.yml
pom.xml
```

将本目录中的 `App.java`、`HelloController.java`、`app.yml`、`pom.xml` 按上表放入工程。

## 运行

```bash
mvn solon:run
# 或
mvn package && java -jar target/demo-1.0.0.jar
```

访问：`http://localhost:8080/hello?name=solon`
