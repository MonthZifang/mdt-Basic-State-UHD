[go-Mindustry](https://github.com/tomorrowsetout/go-Mindustry)

<div align="center">
  <a href="https://github.com/MonthZifang/YUEYUEDAO-TECH">
    <img src="./md/logo.png" alt="月月岛科技 Logo" width="720" />
  </a>

  <p><strong>月月岛科技维护 MDT Basic State UHD</strong></p>

  <p>
    <a href="https://github.com/MonthZifang/YUEYUEDAO-TECH"><strong>查看月月岛科技详情</strong></a>
  </p>
</div>

# MDT Basic State UHD

这是一个 Mindustry 原版服务端插件，提供基础状态类功能：

- 进服弹窗
- 帮助菜单
- 换图投票
- 状态栏显示

## 配置文件

插件会把配置文件生成到：

```text
config/mods/config/mdt-basic-state-uhd/config.json
```

不会再在其他目录创建新的配置文件。

## 构建

```powershell
.\gradlew.bat jar
```

输出：

```text
build/libs/mdt-basic-state-uhd.jar
dist/mdt-basic-state-uhd.jar
```
