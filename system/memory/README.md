# Taku 记忆（0.16.0 开发组件）

[English](README.en.md)

0.16.0 让检索更快，返回结果不变。0.15.1 修复五个 bug（Agent 读不到提醒列表、emoji 让卡片超长被拒、首页 AI 的卡可能一直挂着、同类新卡排在下面、新版 Taku 首页可能被当作记录来源）。0.15.0 按首页 PR #33 的接口说明对齐首页卡片的生命周期和用户反馈：记忆发到首页的卡到期、被用户删掉或关掉开关时都会收回，用户对卡片的操作读回到提醒上。0.14.0 可以把提醒写成描述，交给 Taku 首页的 AI 设计卡片（实验，默认关闭）。0.13.0 让主动提醒也能以卡片出现在 Taku 首页的卡片流里（实验，默认关闭）。0.12.0 针对 0.11.0 的短处做了优化：整理更常写"最晚哪天得办"并标出哪些时间要到场，冲突只拿要到场的事比，消息提醒发出前再复核一次，"今天有变化的事"去掉滚动和一路更新的噪声。0.11.0 让整理写下"最晚哪天得办、错过的代价、要一起想到的"并用进用户卡，主动开口时单独标出可能的冲突、把一天里的状态变化交给睡前提醒，提醒可以一键交给 Agent。0.10.0 新增实验性的"主动开口"（默认关闭）：每晚整理后给睡前提醒，新通知碰到记忆里的事时提醒一句。0.8.2 让用户卡里"接下来两天的安排和截止"更适合主动提醒：本人的预约不再被隐私规则拦下，放不下时按错过的代价取舍，新增"最近一天新出现的待办"。0.8.1 在 0.8.0 之上让整理线程不再带上助手背景（整理开销降到约 1.6 万 token），让助手回消息时用上用户卡里相关的安排和分量，给"接下来两天的安排和截止"去重，并让整理模型列表如实标出"跟随 Taku AI"当前能不能用（见下文）。0.8.0 在 0.7.0 之上补齐英文隐私规则、整理模型可选并大幅降低整理开销、改进助手的主动提醒，删除后通知已开始的对话，并换用量化向量模型（安装包约 37MB）。需配套同一提交构建的 Broker（`agentos-task-broker`：记忆整理 `MemoryGen`、看板和用户卡注入 `HarnessContext`）。只更新记忆 APK 时，夜间整理会因 Broker 不认识整理请求而失败并在 30 分钟后重试，不影响采集、检索和逐条分析。构建前先运行 `python3 scripts/prepare-memory-vectors.py` 取回固定版本的向量依赖（见下文）。批分析协议、助手 v59 的回复语言与 `memoryFocus`、系统展示 v3 / 0.2.1 与消息协议 v2 沿用 0.6.0。

## 0.16.0：检索更快，结果不变

Agent（`memory_search`、open-access `search`）、建议页和用户卡共用的检索，现在用更少的时间返回完全相同的排序结果。在云端测试机上，一句完整的问题在 1 万条记忆时从约 29 ms 降到约 12 ms，5 万条时从约 159 ms 降到约 55 ms。

- **向量和关键词并行**：查询向量和证据向量前 30 在 `memory-query` 线程上算，调用方同时扫关键词和节点文本。最近 64 个查询向量会缓存。模型首次加载用单独的锁，冷启动不再挡住别的检索。
- **只保留前 30**：证据向量放在普通数组里，打分时边算边保留最好的 30 个。分数相同时保留靠前的一条，和以前的稳定排序一致。
- **节点只准备一次**：节点的小写文本和 `related` 集合在加载时算好；一跳扩展每次检索只建一次反向表。
- **关键词 SQL 更省**：覆盖索引 `memory_kw(first_seen,id,title,head,fresh)` 让按时间倒序的扫描只读索引。已有数据库由 15 分钟维护任务补建。没有大小写字母的词（中文、数字、符号）用 `instr()` 代替 `LIKE`；含字母的词仍不区分大小写。
- **清理限频**：检索前的清理最多每分钟一次，不再每次都做；改设置后下一次检索会先清理。

1k、1 万、5 万条记忆下，16 个查询的排序结果和旧代码逐条一致。调试版新增只读、仅 root 的 `benchSearch`，可在设备上测每个阶段的耗时。还没在设备上测。详见 [0.16.0 验收](../../docs/reviews/taku-memory-0.16.0-2026-11-20/README.md)。

## 0.15.1：修复五个 bug

#38 和 #33 合入 main 后，按首页的真实代码重新对照，修了五处：

- **Agent 读不到提醒列表**：`nudges` 没在 `org.agentos.memory.open-access` 的白名单里，Agent 调不到。现在放行，并在文档里补上命令。
- **emoji 让整张卡发不出去**：首页按 UTF-16 数置顶标签（≤24）和副标题（≤32），emoji 占两个；以前按字符截断，名字或提醒里有 emoji 时可能超长被拒收。现在按 UTF-16 截断，不拆开 emoji。
- **首页 AI 生成的卡可能一直挂着**：这种卡没有自己的过期时间，以前只在夜间整理成功后或下一条提醒时收回。现在每 15 分钟的维护任务也会检查：本地判断有到期的卡才收回，最近三天有卡才读回反应，开关关了就全部收回。读回更勤，也更不容易被首页 50 条的共用日志挤掉。
- **新版 Taku 首页可能被当作记录来源**：记忆本来不记录自己和 Taku 助手（`org.agentos.assistant`），但新版首页和 Taku 对话搬到了 `org.agentos.agent`，不在排除名单里。用户在“记录哪些应用”里选了它，记忆就会把首页上自己的提醒卡片和 Taku 说过的话当成新记录，自己喂自己。现在记忆自己、两个 Taku 应用、系统展示和 System UI 一律不记录，选了也不生效，也不出现在应用列表里；`status` 的 `neverObserved` 列出这些应用。
- **同类提醒里最新的排在最下面**：首页对相同 rank 按发布先后排。现在 rank 加上一个随时间增长的极小量（现在约 0.018，一年只涨 0.0003），同类新卡在前，三档顺序不变。

组件测试新增 5 项检查，`cut` 和 `rank` 在 JVM 上 9 项检查通过；测试机仍未跑。详见 [0.15.1 验收](../../docs/reviews/taku-memory-0.15.1-2026-11-10/README.md)。

## 当前 Feed 事件协议

当前首页已取消 100 张上限，也不会淘汰旧卡片。Memory 使用 `/events` 有限回放读取持久化反馈，将已处理 checkpoint 保存为 kv `feedFeedbackCursor`；断线保留旧游标，重试按事件 cursor 去重。每条提醒显示最近 20 条反馈。接受按钮操作显示 `selected`（“选择了卡片操作”），不将 `phase:accepted` 误报为已完成外部操作。GUI 建议页、Agent 公开的 `nudges` 和现有 root/Broker 的 `nudge` 读取同一份反馈。旧版首页无 `eventCursor` 时保留 `recentActions` 兼容读取。以下 0.15.0 记录描述的是旧接口。

同一条提醒的通知、系统提示和首页卡片保持一致。读回发现用户在首页删了卡、点了卡上的按钮或把它交给 Taku 时，记忆收起这条提醒的通知和提示；只置顶不算处理过，通知和提示都保留。用户从通知或建议页把提醒交给 Taku 时，记忆收起提示和旧通知（通知随后显示交接状态），并收回消息提醒在首页的卡片，用户置顶过的除外。睡前提醒的卡片保留，直到下一晚的新卡替换它。

## 0.15.0：按首页 PR #33 对齐卡片的生命周期和用户反馈

Vela 的首页 PR #33 带来了《首页推荐接口》（`docs/feed-recommendation-api.md`），写明了几条以前没注意到的规则：

- 过期的卡不再显示，但仍留在存储里，占所有调用方共用的 100 个名额；用户删掉的卡也一样。
- `feed/publish` 超出名额时会悄悄挤掉最早发出的卡，可能是别的应用的；`feed/generate` 则返回 `FEED_FULL`。
- 没有回调。用户的操作只能轮询 `feed/list` 的 `recentActions` 读到，而它只留最近 50 条。
- `feed/generate` 没回话时结果未知，卡可能已经发出。
- 样例卡的 rank 在 0.70–0.99 之间，大于 0.99 才排在全部样例前面。

0.15.0 按这些规则改了记忆这边：

- **登记并收回所有卡**：固定样式的卡也记进 kv `feedExpiring`，到期就收回。以前只收首页 AI 生成的卡，固定样式的消息卡会一直占着名额。用户删掉的卡，下次清理时收回。
- **关掉就收回**：关掉「放到首页卡片流」或「主动开口」时，记忆发到首页的卡全部收回。首页暂时连不上的，留到夜间整理时再收。
- **读回反应**：
  - 推卡前、每晚整理后和打开记忆应用时，各读一次 `feed/list`，把最近三天提醒卡上的操作记进提醒表的新列 `feedback`；
  - 每条是 `{at, op, origin, effect}`，去重，最多 20 条；
  - 已经滚出日志、但还在 `removed[]` 里的删除也记下，origin 为空；
  - 整理成 `reactions`：`asked`（点了交给 Taku 的按钮、打开了草稿）、`opened`（打开了来源应用）、`pinned`、`removed`。
- **看得到**：「建议」页最近 7 天的提醒下面显示一行「首页卡片：…」。`nudge` 返回 `feedback`。新增 `nudges` 读接口（`days` 1–7、`limit` 1–30），和那一栏同源。签名消费者和 open-access 的内容查询都受 Agent 记忆查询开关控制，关闭后明确返回失败。
- **排序和图标**：记忆的卡原来夹在样例中间，现在都高于 0.99：可能冲突 1.3、消息 1.2、睡前提醒 1.1。消息卡的置顶图标从 `list` 换成 `chat`。
- **没回话时先找卡**：`feed/generate` 没回话或回复读不出时，在首页上找这次调用之后新出现、按钮和描述一致的 `agent-generated:` 卡。找到且通过检查就留下，不通过就收回；找不到才推固定样式。
- **首页已满**：`feed/generate` 返回 `FEED_FULL` 时，这次只发通知，不再推固定样式，免得挤掉别的应用的卡。
- **调试**：调试包新增两个 root 专用钩子：`deliverNudge` 不问模型，直接发一条提醒；`feedTidy` 立刻做一遍清理，带 `withdraw` 时全部收回。
- **测试替身**：
  - `feed/list` 改成首页的形状：卡片摘要、`pins`、`removed`，以及 `recentActions` 的 `{at, origin, op, cardId, detail}`；
  - 新增 `feed/remove`、`restore`、`pin`、`unpin`、`action`、`ask-taku`，可以带 `origin:"gui"` 模拟屏幕上的操作；
  - `feed/stub` 新增 `full`、`lost`（卡发出了但不回话）和 `silent`（没发也不回话）。

反应目前只记录、显示和供 Agent 读取，还没有用来调整什么时候开口。

**结果**：26 张固定样式卡按 Vela 分支最新的组件目录重新校验，全部通过。读回逻辑的 6 项检查在 JVM 上全部通过。测试机这次被 Mac 上别的任务占满 CPU，没能开机，组件测试和替身端到端都还没跑。详见 [0.15.0 验收](../../docs/reviews/taku-memory-0.15.0-2026-11-09/README.md)。

## 0.14.1：重写交给首页 AI 的描述

描述里写明元素树和分页写法、条目不编号、消息用 Inset 引用、summary 原样给出（和固定样式卡一致，首页取前 24 个字当置顶标签）、actions 缩进且不转义斜杠。同样 26 条提醒交给首页工程自己的生成代码（`gpt-6-luna`），能用的从 88%（91/104）升到 94%（49/52），结构错误从 7 次降到 0（8 遍 208 段），照抄编号从一半以上的卡降到 0。详见 [0.14.1 验收](../../docs/reviews/taku-memory-0.14.1-2026-11-09/README.md)。

## 0.14.0：把提醒写成描述，交给首页 AI 设计卡片（实验，默认关闭）

首页新增了 `feed/generate`：传一段描述，首页用它配好的模型路由（Android Magpie 的 `agent-ui.text`）生成卡片，id 为 `agent-generated:<uuid>`。

- **开关**：「放到首页卡片流」下面的「让首页 AI 设计卡片」，`configure` 的 `feedDescribe`。
- **描述**（`FeedCards.describeEvening` / `describeMessage`）：
  - 原样照抄的条目、消息和 Taku 要说的话；
  - 原样使用的 actions JSON，交给 Taku 的话仍写明"不要发送"；
  - 几条写法要求：按钮的 `on` 字段、不用需要头像的组件、Pager 和 Dots 要挂上、输出顺序。
- **检查**（`FeedCheck`）：用 `feed/get` 读回卡片，要求：
  - actions 不变，空白不计；
  - 每个动作都有按钮；
  - 每段文字原样出现，至少前 24 个字。
- **兜底**：首页没排出来或排的不合要求，就收回、再试一次，还不行推固定样式卡。首页没有回应的那次不重试。
- **到时收回**：生成的卡不会过期，记忆记下 id 自己收回：
  - 消息卡 24 小时，睡前提醒卡第二天 23:59；
  - 每次推卡前和每晚整理后各收一次。
- **后台线程**：推卡挪到单独的后台线程，一次一件，通知不等它。
- **调试**：`feedPreview` 带 `describe: true` 返回描述。
- **测试替身**：
  - 新增 `feed/generate` 和 `feed/get`，不调模型；
  - `feed/stub` 可切换为改写按钮（`tamper`）或拒绝（`refuse`）。

**结果**：

- 26 条提醒的描述交给首页工程自己的生成代码，模型 `gpt-6-luna`，两遍都是 24 张通过首页校验，也都过了记忆的检查；两遍里 25 条至少有一遍能用。
- 描述的写法影响很大：最早的写法 14 张通过首页校验，但没有一张能用，按钮都没接上动作。
- 测试机上装替身走通了留下、拦下改写、被拒后改推固定样式和到时收回。
- 还没在装了首页的手机上跑过。

详见 [0.14.0 验收](../../docs/reviews/taku-memory-0.14.0-2026-11-07/README.md)。

## 0.13.0：主动提醒放到 Taku 首页卡片流（实验，默认关闭）

Taku 首页（`system/agent-app/modules/taku-home`，Vela 的 `vela/TAKU-new-UI` 分支）有一个卡片流，别的模块可以推 JSON 卡片：信封 `taku.feed-card/1`，正文是 `taku-feed-1` 组件目录里的 json-render 结构，经 `content://org.agentos.agent.home-feed` 的 `feed/publish` 推送（见该模块的 CONTRACT.md）。

- **开关**：「设置 → 整理与助手 → 放到首页卡片流」，只在主动开口打开、且本机装了带卡片流的首页时出现；`configure` 的 `feedCards`。
- **睡前提醒卡** `memory.brief.<id>`：标题和最多 9 条，每页 3 条可翻；"收起"和"让 Taku 安排"（交给 Taku 排明天，回复只起草不发送）。新的一晚收回昨晚那张，第二天 23:59 过期。
- **消息提醒卡** `memory.nudge.<id>`：引用原消息，下面是 Taku 记得的那句，可能冲突时带标签；"打开应用"（有来源应用时，否则"收起"）和"起草回复"或"交给 Taku"。24 小时后过期。
- **只取提醒本身**（`FeedCards`、`ProactiveNotice.feed`）：不另调模型；通知或系统展示照旧，推卡失败只记日志。提醒表新增 `card` 列，`nudge` 一起返回。
- **隐私**：首页卡片流对本机应用开放（不需要权限），所以消息原文和 Taku 记得的内容放上首页后，本机其他应用也能读到；开关说明写明了。
- **调试与测试**：调试包新增 root 专用的 `feedPreview`（按给定内容拼一张卡，不调模型、不发出），`proactive` 可带 `card: true`；`build-tests.sh` 另出首页卡片流替身 `TakuHomeFeedStub.apk`（占用首页的 authority，测完要卸载）。

26 张卡片（21 晚睡前提醒、5 条消息提醒）和测试机上经真实路径推出的 3 张全部通过首页自己的 `validateFeedSpec`；在首页工程的网页组件预览里渲染正常。测试机上装替身后，真的问模型、发出提醒：睡前提醒和消息提醒都推成卡片，新的一晚收回旧卡，关掉开关后不再推。还没在装了首页的手机上看过。详见 [0.13.0 验收](../../docs/reviews/taku-memory-0.13.0-2026-11-05/README.md)。

## 0.12.0：针对 0.11.0 短处的优化

- **整理器**（`MemoryGen`）："最晚哪天得办"凡是得提前做点什么的都写（改约、按快递天数下单、确认、回复、缴费、取件、准备），能推算就推算；新增 `attend`，标出这个时间用户是否得亲自到场。
- **用户卡**（`Recall.dueSoon`）：要到场的时刻标"要到场"；放不下时，写了日子的待办和物品按截止算，"前要办"不再额外加分，整理器写的分量加分减半；同一时刻同一个人、其中一条要到场的两行合为一条。
- **冲突**（`Proactive`）：本地只拿标了"要到场"的安排比时段（旧图谱没有标记时照旧）。
- **复核**（`Proactive.check`，Broker 新增 `check` 类）：消息提醒决定开口后，再用 `gpt-6-luna` 只查两类错（把截止、送达、入住时间说成冲突；把不相干的事扯在一起），查出就不发。每天约一次、每次约 4.5 千 token。
- **"今天有变化的事"**（`Recall.changes`）：原来的时间到昨晚已经过去的，不算改期（每周例会滚到下周、一路更新的线上问题）。

用 0.12.0 整理器重建 22 天合成时间线的图谱：写过"最晚哪天得办"的事 9 → 41；80 个时刻卡片覆盖 74 → 75（新规则放到旧图谱上仍是 74）；睡前提醒提到晚间时刻 62、62 → 65、64/70；本地标冲突 27 → 14 次；复核离线拦下说错的 5/5、误拦有用的 3/29，测试机上说错的开口 4 次拦下 3 次、有用的 6 次全放行。详见 [0.12.0 验收](../../docs/reviews/taku-memory-0.12.0-2026-11-05/README.md)。

## 0.11.0 新增：整理写下提醒要用的字段，提醒能交给 Agent

- **整理写进图谱**（`MemoryGen`、`MemoryStore.applyGraph`）：节点新增 `due`（最晚哪天得动手，只在和事情本身的日子不同时写）和 `stake`（错过的代价，一句话，证据里没有就空），`related` 要连上提醒时应一起想到的（要穿的、要带的、背后的原因）。
- **用户卡用上它们**（`Recall.dueSoon`）：写了 `due` 的按它进"接下来两天的安排和截止"，标"前要办（本身在 …）"；带"（分量：…）"，放不下时分量大的优先；后面跟"↳ 一起想到：…"。
- **冲突单独标出**（`Proactive.slot`）：新通知约的时段和那天已有安排的时刻重叠时，本地标"可能冲突"再交给模型，由模型确认。
- **状态变化进睡前提醒**（`Recall.changes`）：过去一天改了时间、取消、办完的事列为"今天有变化的事"。
- **交给 Agent**（`NudgeAction`、Broker `nudgeTask`）：通知和建议页的提醒上有"交给 Agent"。Broker 从记忆服务读回提醒和当时的背景，在原 Agent 里排一个只读任务（核对、给办法、起草回复不发送），同一条只交一次；建议页随后可"查看 Agent 结果"。提醒表新增 `context`、`task` 两列（首次打开时补上）。
- **调试包**：`maintain` 可带 `graphOnly`（只重建图谱），`configure` 可带 `agendaFields`（用户卡用 / 不用这三个字段，做对照）。

22 天合成时间线用当前整理器重建图谱后：80 个标注时刻卡片上有那件事 74/80（不用这三个字段 71）；睡前提醒提到晚间时刻 62、62/70（0.10.0 为 59，不用字段 54）；195 条通知回放开口 20 次，有用 16、用处不大 2、说错 2（0.10.0 为 13 / 5 / 2）。详见 [0.11.0 验收](../../docs/reviews/taku-memory-0.11.0-2026-11-04/README.md)。

## 0.10.0 新增：主动开口（实验，默认关闭）

「设置 → 整理与助手 → 主动开口（实验）」打开后，记忆不再只在用户问的时候用上（`Proactive`、`ProactiveNotice`）：

- **睡前提醒**：每晚整理完（20 点以后，一晚一次），把用户卡交给模型写一段最多 8 条的提醒，按错过的代价排。
- **新通知碰到记忆里的事**：先在手机上判断（不调模型）通知有没有碰到卡片上的人、共用编号或代码、问到的那天已有安排、和习惯有关；碰到了才把这条通知和相关的几行记忆交给模型，决定要不要提醒。只看通知，页面截取的聊天列表先不触发。
- **送达**：系统展示连着时出提示，否则发通知；建议页列出"Taku 主动提醒过的"。消息提醒每天最多 8 条，两分钟内只问一次模型，同一件事 12 小时内不重复；像指令的、带链接的通知不叫醒模型。
- **模型**：Broker 新增整理类型 `brief`、`nudge`（推荐 `gpt-6-luna`），和整理一样受 Taku AI 的 Memory 开关约束，线程不落盘、不带工具、不带助手背景。每次约 4.5 千 token。

22 天合成时间线：睡前提醒提到了晚间 70 个该提醒时刻中的 59 个；195 条通知里本地判断叫醒模型 32 次、开口 20 次（每天约 1 次），更强的模型打分 13 条有用、5 条用处不大、2 条说错。小雨问日料店名、问阿杰想要什么、凌晨航班取消、约明晚吃饭撞上已有约会，三次都开口了。调试包新增 root 专用的 `proactive`（指定时刻算一次）和 `proactiveReplay`（整段回放本地判断）。详见 [0.10.0 验收](../../docs/reviews/taku-memory-0.10.0-2026-11-03/README.md)。

## 0.8.2 新增：让记忆在该开口的时刻摆出对的事

22 天合成时间线里标好的 80 个"该主动开口的时刻"，大多要靠几天前记下的线索（中位数隔 53 小时），逐条判断刚记下的内容最多接住 3 个。这一版只改用户卡里"接下来两天的安排和截止"怎么选，不调模型、不改整理：

- **本人的预约不再被拦**：图谱事项里的"体检""复查"，紧挨在前面的是别人（"妈妈体检"）或用户不是主语时仍按原规则不放；用户自己约的（洁牙复查、预约年度体检）照常提醒。别人的病情和诊断仍然一律不放。
- **放不下时按错过的代价取舍**：有惩罚的截止、"需在……前"的期限、对别人的承诺、生日婚礼、涉及金额的优先，日报和"无需在家"这类例行通知靠后；同一天同一件事的两个节点只留一条。
- **最近一天新出现的待办**：一天内新出现、还没到两天之内的待办，当晚就让助手知道（最多 4 条）。
- **某天起可办、还没办完的事保留一周**（"13 号起可领取"）。

每个时刻卡片上有那件事：68/80 → **74/80**（晚间 61/70 → 67/70），没有因为取舍丢掉原来接住的；卡片长度中位数约 1,670 → 1,930 字。只拿卡片、不带工具让 `gpt-6-luna` 写睡前提醒（以后主动提醒要走的轻量路径，每次约 5.6 千 token），晚间 70 个时刻被提到 57 → **62**。完整助手会自己检索，两版回答差别不大，但每次要 15 万–23 万 token。详见 [0.8.2 验收](../../docs/reviews/taku-memory-0.8.2-2026-11-03/README.md)。

## 0.8.1 新增

- **整理线程不再带助手背景**：Broker 原来给每次 `turn/start` 都补上助手的背景（设备环境、技能目录、看板和用户卡副本），整理线程也被补上，助手执行过任务之后每次整理调用从约 1.25 万 token 涨到约 4 万。现在整理线程只带整理说明和自己的输入（`MemoryGen.owns`），同样 7 条通知一次整理约 1.6 万 token。
- **回消息时用上相关的安排和分量**：助手帮用户回消息或处理通知时，先想清楚这件事牵涉谁，再把用户卡上这些人与这件事相关的安排（时间、地点）和分量（为什么重要）用进回复或建议（Broker `HarnessContext`）。22 天合成时间线里，触发消息从 5/7 到两次都是 7/7：小雨问阿杰的生日礼物时，拟的回复里顺带约好"22 号晚上 6 点老地方"；凌晨航班取消时，说明这次合作"竞品也在争取"，所以优先保住和王总的会面。
- **两天安排去重**：图谱里同一件事常有几个相近的节点（"妈妈体检当天中午打电话关心""妈妈体检后中午打电话"），"接下来两天的安排和截止"里同一天、标题相近的只留一条。
- **跟随 Taku AI**：main 已把这一项改为使用 Magpie 连接上"记忆"场景的路由组（`memory.text`），没有 Magpie 连接或路由组不可用时不回退到推荐模型，整理中断并在设置页显示原因。0.8.1 在「整理模型」列表里标出当前不可用（"需要 Magpie 连接"），点选时提示先到 Taku AI 配置，不保存这个选择；Broker 的 `organizeModels` 多返回 `systemReady`。
- **Taku AI 的 Memory 开关**：main 起这个开关在 `Settings.Global` 的 `taku_ai_app_memory`（不再是 `/data/local/agentos/ai/settings.json`）；关掉时不整理，打开后恢复，冒烟按新位置验证。

22 天合成时间线回归（沿用 0.5 整理出的看板、用户卡和图谱，同一标准阅卷）：跨话题难题两次 10、10 / 11（0.8.0 为 10、9），联想 11/11，触发消息 7/7、7/7（0.8.0 为 5/7），睡前复盘 24/25（0.8.0 按同一标准为 22/25）。详见 [0.8.1 验收](../../docs/reviews/taku-memory-0.8.1-2026-11-02/README.md)，其中也有最新 main（`a4bc391`）上的全新安装复测。

## 0.8.0 新增

- **英文隐私规则**：`Privacy` 的每条规则都有中英文两套：别人的病情、感情、财务、私下的离职打算不留，就医类事件只在和用户要做的事一起时保留，同事离职只留交接；本人的诊断、用药、收入和账户不留；英文的指令式内容丢弃；删掉的条目按英文日期和虚词归一后比对。今天新出现的原话也按同一条线过滤，并能按英文词匹配消息。
- **整理模型与开销**：「设置 → 整理与助手 → 整理模型」可选推荐（图谱和用户卡 `gpt-6.1-sol`，看板 `gpt-6-luna`）、跟随 Taku AI 文字模型，或指定一个 Codex 模型；选的模型不可用时用推荐的。每次整理用整理说明替换运行时自带的编程助手说明、关掉用不到的工具，固定开销约为原来的三分之一：同样 7 条通知，一次整理约 3.6 万 token（0.7.0 约 7.9 万）。设置页显示最近一次整理用了多少 token、哪些模型。整理线程不落盘，旧版本留下的整理会话在 Broker 启动后第一次整理时删除。
- **主动提醒**：按消息挑出的用户卡新增"接下来两天的安排和截止"，来自记忆图谱里今天到后天的约定、待办和物品（取件、付尾款这类一次性的事，用户卡按设计不收），按时间排列，同样过隐私规则。助手被要求在回答完之后，用一两句顺带提醒今天到后天的重要日子、截止和会冲突的约定，同一段对话里不重复。
- **删除后通知助手**：用户删除或纠正记录、删改看板或用户卡后，已经开始的对话在下一轮收到一次说明：此前来自记忆的看板、用户卡、记录和原话可能已作废，不要再引用，以重新查到的为准。说明里不复述被删的内容。已经发给模型服务的内容无法撤回。
- **量化向量模型**：改用 bge-small-zh-v1.5 的动态量化版（约 24MB，原 fp32 约 95MB）。在 22 天合成数据上与 fp32 相比：文档向量平均余弦 0.990，72 个查询的第一名一致 91.7%，前 10 重合 88.5%，fp32 前 5 有 99.2% 仍在前 10；文档编码约快 2.5 倍。升级后旧向量（文档和图谱节点）自动按新模型重算，模拟器上 1537 条约 30 秒。

22 天合成时间线回归（沿用 0.5 整理出的看板、用户卡和图谱）：跨话题难题两次 10、9 / 11（0.5 为 6、8），联想 11/11、触发消息 5/7、睡前复盘 21/25 与 0.5 相同。详见 [0.8.0 验收](../../docs/reviews/taku-memory-0.8-2026-10-29/README.md)。

## 0.7.0 新增：不遗忘的存储、手机端向量、整理出的看板和用户卡

- **存储**：同一页面只存相对最近版本新增的行（行库去重），默认不遗忘（保留期限可选 1／7／30 天）。通知通过通知读取权限（`NotificationTap`）获得，只读所选应用的标题和正文，跳过验证码、常驻和受保护通知。
- **检索**：关键词 + 手机端 bge-small-zh-v1.5 向量（ONNX Runtime，不联网）+ 记忆图谱节点，按排名融合，再沿图谱关系扩一跳。
- **夜间整理**（`Organizer`，经原助手 Broker 使用用户自己的模型账号）：把新内容整理成图谱节点、「最近在忙什么」的看板和简短的用户卡（本人习惯 ≤10 条；身边重要的人 ≤12 个，每人备注 ≤3 条和重要日子）。每晚 23 点；那一小时没开机、休眠或没网，之后第一次机会补跑；失败后等 30 分钟再试。
- **助手怎么用**：看板整块交给助手；用户卡按每条消息挑——常驻本人习惯和核心家人，加上接下来 3 天有约定或日子的人、这条消息牵涉到的人和习惯、今天新出现但卡片还没整理进去的相关原话（引号标出，像指令的话和链接不放）。备注里的日期都已过去会标"日期已过"。设置里可分别关掉"助手参考看板／用户卡"。
- **隐私**：保存和展示前都按规则再过滤一遍（`Privacy`）。用户卡里别人的病情和身体细节、身材、感情和家庭矛盾、财务、私下的辞职跳槽打算一律不留；体检、住院这类事只有和用户要做的事写在一起时才留（"妈妈周五体检，中午打电话问问"）；同事离职只留交接。本人的诊断、用药、收入和账户不留。长号码打码，像指令的条目丢弃。
- **删除和纠正**：用户删除或纠正一条记录时，引用过它的图谱节点版本全部删掉（证据列表是累积的，所以正好是整理过它之后的版本），这些版本引用的其他记录、以及纠正后的原文，在下一次图谱整理时先重新整理；可能含有它的看板和用户卡版本也删掉，整理进度回退到之前的版本并尽快重新整理。整理中途遇到删除或纠正，不保存旧结果。节点编号不复用。
- **用户编辑**：「设置 → 整理与助手」里可查看看板和用户卡，删单条、删整个人或清空重建。删掉的内容除了告诉整理模型不要写回，保存和展示前还会按名字和相近说法直接拦下。
- **系统 AI 开关**：夜间整理和逐条分析一样受「Taku AI」约束——系统 AI 整体暂停或关掉 Memory 的 AI 时不整理，整理途中设置变化则丢弃结果；调用记录计入同一份最近调用。
- **界面语言**：新增的设置、看板和用户卡页面使用与 0.6.0 相同的中英文资源；看板和用户卡的内容是整理模型写的中文，不随界面语言翻译。
- **构建开关**：模拟时刻（`testNow`）、指定时刻整理（`maintain {now}`）、单独重建用户卡（`buildProfile`）只在 `TAKU_DEBUG_HOOKS=1` 的调试构建里存在，且只允许 root；正式构建拒绝这些参数。正式构建必须打包向量模型。

新增接口（签名调用方，需开启 Agent 查询）：`board`、`profile`（`forAgent` + `text` 时按消息挑选）、`agenda`。本应用或受控 root：`maintain`、`clearDerived`、`deleteBoardItem {part,name}`、`deleteProfileItem {kind: me|person|note, item, note}`；Broker 内部：`genInput`、`genResult`。Broker 的记忆网关另有 `organizeModels`（整理模型列表）；`board`、`profile` 的结果带 `revokedAt`。

向量依赖：`system/memory/vector-dependencies.json` 固定 ONNX Runtime Android 1.29.0 AAR 与 Xenova/bge-small-zh-v1.5 量化版 `model_quantized.onnx`（约 24MB）的地址和 SHA-256，`scripts/prepare-memory-vectors.py` 下载校验到忽略的 `system/memory/downloads/`，并为 `Android.bp` 准备 `downloads/assets/bge/`（模型不压缩存放）。`build.sh`（含 `npm run build:android`）首次构建会自动运行它，之后只校验；ROM 构建前需先运行一次。`Android.bp` 的 `TakuMemory` 必须保留 `use_embedded_native_libs: true`（否则 Soong 会丢掉 AAR 里的 `libonnxruntime.so` / `libonnxruntime4j_jni.so`），清单也不能写 `extractNativeLibs="true"`；`system/memory/test-host.sh` 在主机上校验这一打包契约，并验证缺原生库时召回降级为纯关键词、进程不崩。也可用 `TAKU_ORT_AAR`、`TAKU_BGE_DIR` 指定别处的依赖。APK 约 37MB，主要是模型和 ONNX Runtime。

0.4–0.5 分支验收（emulator_verified，2026-10-28 北京时间，专用 AVD `TakuRecall`，Android 16 / API 36 / ARM64，基线 `96e2409`）：[记录](../../docs/reviews/taku-memory-0.5-2026-10-28/README.md)。合并后的 0.7.0 复测：[记录](../../docs/reviews/taku-memory-0.7-2026-10-28/README.md)。0.8.0：[记录](../../docs/reviews/taku-memory-0.8-2026-10-29/README.md)。同版真机、完整 ROM、冷启动和长期耗电未验收。

一个独立 APK `org.agentos.memory` 同时承载后台自动记忆、管理界面与主动建议，使用独立 UID 和应用私有数据库。Agent、模型凭据、执行队列仍由已有助手、Magpie 和 TaskBroker 管理。

## 0.6.0 系统 AI 接入

新安装默认「跟随系统文字模型」，在 Android 系统设置 → Taku AI 统一配置；Memory 设置保留分析开关、记录来源和显式模型覆盖，并提供同一系统页面的入口。已有用户明确选择的模型保留，切回跟随后才使用系统默认值。模型变化会撤销旧分析租约，记录仍保留在原队列。

普通系统文字模型可按严格 JSON 协议整理记忆，显式选择 Jev 时继续复用原 Decisions 实现。没有第二份模型凭据或数据库，也不因统一设置自动开启记录、分析、查询或主动建议。接入与验证方式见 [Taku 系统 AI](../ai/README.md)。

## 用户流程

1. 打开「Taku 记忆 → 设置」，统一开启后台自动记忆，选择允许记录的应用，在 Android 设置中启用观察服务。之后浏览这些应用即可自动形成记录，不需要逐条点击“记住”。
2. 在底部「记忆」按日期查看卡片，按内容、标题或分类关键词搜索，也可组合分类与来源筛选；超过 20 条时继续加载。打开独立详情阅读页，展开「记录信息」查看来源包名、采集时间、ID 和版本，可以纠正，或通过右上角菜单查看修改历史、删除。纠正保留最近五个历史版本；删除同时移除正文、历史与检索结果，同一原始页面 30 天内不自动重新记录。
3. 默认只在本机保存文字，并进入持久待分析队列。选择 Magpie 模型也不会自动上传；还需独立开启「模型分析（Jev / Magpie）」。开启后合并窗口内的记录，每批最多五条共用一次请求，经原助手的签名接口与手机内 Magpie 分类，使用已有服务和凭据，不保存另一套 API key。关闭分析会保留队列，之后开启自动补分析。
4. 本地记录、模型分析、Agent 查询和主动建议各有开关。暂停本地记录只停止新增采集，已有队列仍按分析开关处理；关闭主动建议只停止提醒，不阻止已开启的分类。开启分析、Agent 查询和主动建议，且模型判断达到门槛时，可以在主屏显示建议；点击前没有 Agent 任务。接受后由原 TaskBroker 创建一个只读任务，新建原生 Codex 会话并调用真实记忆工具。结果可以从「建议」查看。

「观察」记录的是所选应用当前可访问的文字，不是所有应用历史或后台屏录像。不同页面记录按时间保留，用户纠正有版本；跨应用的同一件事由夜间整理合并到图谱节点和看板，原始记录不改写。

主屏提示复用已有 `TakuSurfaces` v2 系统展示接口（宿主 APK v3 / 0.2.1）；开发模拟器也须安装 main 中对应的宿主版本。它是已有系统组件，不是第二个记忆 App。

## 0.5.0 内容标题与具体建议

卡片和详情标题从已保存文字中提取活动名、聚餐安排或路线，应用名保留为较小的来源标识。例如 Calendar 显示为 **Taku design review (Demo)**，聊天显示为 **周六午餐 · The Hummingbird Cafe**，地图显示为 **Marina Bay Sands → Singapore Flyer**。摘要优先显示内容，减少导航控件文字；用户纠正的标题优先保留。

建议明确说明下一步：**生成午餐清单、生成评审准备清单、生成活动准备清单、核对路线详情、提取关键要点**。聚餐提示可带上记录中的人数、时间与人均预算，日程提示带上原日期与时间。接受后，TaskBroker 只接收允许的任务类型，Agent 读取最新记忆并按对应任务生成清单。是否建议仍由 Jev 决定，`none` 不产生提醒。

这些文案在本机提取，不增加模型请求，不改写采集标题、正文、版本或历史。「记录信息 → 原始标题」保留来源标题，Agent API 也继续返回原始证据；旧记录无需迁移或重新分析即可获得新展示。它是有界的展示规则，尚不是通用语义提取、事实消歧或用户内容翻译。

[本轮中英文实测与截图](../../docs/reviews/taku-memory-content-2026-10-22/README.md)。英文仍为默认资源，中文同步维护。

## 模块与运行边界

| 模块 | 职责 |
| --- | --- |
| `ObservationService` | Android 页面变化事件去抖，主屏、解锁及所选应用检查，有界提取文字；平台保护窗口检查、去重和本地落盘；不执行模型分析 |
| `MemoryStore` | 私有 SQLite、增量行库、默认不遗忘（可选 1–30 天）、版本与遗忘墓碑、持久分析队列与租约、图谱／看板／用户卡版本 |
| `Delta`、`Embedder`、`Recall` | 增量存储、手机端向量编码、混合检索与按消息挑选用户卡 |
| `Organizer`、`Privacy` | 夜间整理（经 Broker 生成）、保存和展示前的隐私与指令过滤 |
| `NotificationTap` | 通知读取（需用户授权），只读所选应用 |
| `BrokerClient` | 调用原助手的签名保护接口，不直连模型供应商，也没有 APK 网络权限 |
| `MemoryProvider` | 签名保护的记忆 API；采集不提供外部注入入口 |
| `MemoryActivity` | 记忆、建议、设置三个原生入口 |
| `AnalysisJob` | 系统后台作业处理有界批次，限流等待、失败退避和过期租约恢复；复用 Magpie，不采集屏幕 |
| `MemoryMaintenance` | 每 15 分钟的系统作业：本机清理、补算向量、分析作业恢复，并在到点或需要重建时启动夜间整理；自身不采集或直接请求模型 |
| 原助手 `MemoryGatewayProvider` / Broker `MemoryBridge` | 受限的模型目录、决策、接受建议和任务结果；不暴露任意原生 RPC |

事件稳定 900ms 后才采集；限制主屏和已解锁状态，跳过密码、敏感节点与可编辑输入。通过 Android `takeScreenshotOfWindow` 的保护检查后才记录文字，图像缓冲立即关闭，不存截图。主屏可观察不表示独立后台屏也被采集。`FLAG_SECURE` 等保护不被绕过。

分析队列与本地记录写入同一 SQLite 事务。先合并约 **20 秒**，每批最多 **5 条**，分别标记 ID、版本和来源，在一次 typed Decisions 请求中逐条返回结果；批处理不会丢弃页面、把不同事实合成一条记忆或复用某一条的答案。窗口是最短等待时间，Android 后台调度可能延后执行，不保证每 20 秒准点请求。

批次预算仍为每 **15 秒**最多一次、每小时最多 **60 批**，保存在本机，重启不重置。限流只推迟队列，不丢记录。请求失败从 30 秒起指数退避，最多 30 分钟，仍受小时预算约束。队列使用 120 秒租约；进程中断后通过持久系统作业恢复过期租约，维护作业提供调度恢复。只在用户存储已解锁时处理。

「模型分析」默认关闭，0.1.0 原地升级也保持默认关闭，旧记忆和正文保留，未完成记录排队。关闭分析或切换模型使在途结果失效；已发出的网络请求不承诺撤回。删除、纠正、过期和排除来源会撤销对应队列项；迟到结果不能恢复旧正文。关闭本地记录不会同时关闭分析，关闭主动建议不会同时关闭分析；开关职责可分别控制。

这是可恢复的分析尝试，不承诺网络请求 exactly-once：若进程在服务已处理但本机未落结果时中断，租约恢复可能重新请求；令牌与版本检查防止旧结果覆盖新状态。「已尝试批数」是本地批次预留计数，不能直接作为上游计费次数。

建议门槛为 0.75，展示有效期五分钟，同一观察服务进程中的展示间隔至少一分钟；分析完成不等于必须提醒。App 设置显示待分析数、处理中数量、分析开关、尝试批数和最近状态。

通用开发环境使用 AOSP 测试平台签名，不能称生产签名或第三方沙箱。内容处于 Android 用户解锁后的应用私有存储；记录删除是应用层删除，不承诺对 root 或存储取证不可恢复。后台观察需要一次系统服务授权；本组件不自动开启生产设备的采集权限。

## Agent 与 Harness

原生动态工具提供：

- `taku_phone.memory_search({"query":"关键词"})`：最多十条当前版本记录，返回来源、时间与 ID。
- `taku_phone.memory_get({"id":"记忆 UUID"})`：回读最新正文、版本与来源。查询关闭、删除或过期会明确失败。

这些是已有 Codex Agent 的手机工具，不增加通用 SDK 方法计数，也不是 Codex 内部 `memory/status` / `memory/reset` 的实现。新建原生会话时注册；已有老会话不能因升级 APK 就假定动态工具已经刷新。

Harness 的背景政策说明如何按任务检索和引用记忆，不把整个数据库写进 developer 背景。检索正文始终作为不可信资料，不授予额外权限。接受建议仅保存记忆 ID，Agent 执行时重新读取，删除后不能从任务提示中继续得到旧正文。此路径不读取主屏截图，不附带原会话草稿或附件，不更换原模型连接。

## Android 调用接口

Authority：`content://org.agentos.memory`，状态版本 `taku.memory/2`。调用 `ContentResolver.call(uri, method, JSON参数, null)`，返回 Bundle 的 `result` 字符串：`{"ok":true,"result":{...}}` 或明确失败。

这里的权限表描述签名消费者入口。Taku OS 另提供 `content://org.agentos.memory.open-access`，与本应用 UI 共用存储和管理逻辑，供 Agent 和设备上的应用显式调用。`search/get/board/profile/agenda/nudges/graph/browse` 与签名消费者共用读取实现，统一检查 `queryEnabled`；关闭时明确失败，不修改开关。`status/configure` 等管理方法仍与 GUI 同权，调用 `configure` 可以显式、真实地修改这个运行开关。因此它不是恶意 App 隔离或调用方授权边界；接口不会把读取请求自动转换成开启查询。关闭时，显式 `update` 仍可编辑，但只返回 `id/revision`，不附带旧正文或历史；本应用的本地 GUI 仍可浏览。`nudge` 等 Broker 内部方法仍不在 open-access 白名单中。

| 方法 | 参数 | 权限 |
| --- | --- | --- |
| `status` | `{}` | 签名调用方可读状态 |
| `search` | `query`、可选 `limit`（1–20） | 签名调用方且用户开启 Agent 查询 |
| `get` | `id`、可选 `history` | 同上 |
| `configure` | `enabled`、`analysisEnabled`、`queryEnabled`、`proactive`、`sources`、`model`、`followSystem`、`retentionDays`（0 为永久）、`userName`、`autoOrganize`、`organizeModel`（空为推荐，`system` 跟随 Taku AI，或模型名）、`agentBoard`、`agentProfile`、`nudges`、`feedCards`、`feedDescribe`；调试构建另有 `testNow`（仅 root） | 本应用或受控 root 开发环境 |
| `update` | `id`、当前 `revision`、`title`、`body` | 本应用或受控 root；旧版本拒绝 |
| `forget` | `id` | 本应用或受控 root |
| `nudges` | 可选 `days`（1–7）、`limit`（1–30） | 签名调用方且用户开启 Agent 查询；返回主动开口说过的话、首页卡片 id、`feedback` 和 `reactions` |

`batch`、`evidence`、`reserve`、`task`、`genInput`、`genResult`、`nudge`、`nudgeTask` 为 root Broker 的内部接口，不向普通签名消费者提供管理权限。API 不提供任意文件路径、SQL、账户读取或页面注入。原助手的 `content://org.agentos.assistant.memory-gateway` 同样受签名权限保护，只支持记忆相关操作。

## 构建与复测

在系统仓库根目录，使用已配置的 Android 36 SDK、build-tools 36.0.0 与 JDK 17+：

```bash
bash system/memory/build.sh                      # 正式构建（首次自动准备向量依赖）；回放评测用 TAKU_DEBUG_HOOKS=1
bash system/assistant/build.sh
bash system/memory/build-tests.sh
bash system/memory/test-host.sh                  # 主机端：ROM 原生库打包契约 + 缺原生库降级（不连设备）
```

输出分别为独立记忆 APK、原助手与 Broker、不同签名的合成页面／隔离测试 APK，以及首页卡片流替身 `TakuHomeFeedStub.apk`（只在没装首页的测试机上用，装真首页前要卸载）。测试组件不进入产品 APK 或 ROM。

`test-memory-device.py` 仅允许专用开发 AVD `Taku_ImageIntegration_20261010` / `emulator-5634`，要求独占界面、队列空闲及新装记忆环境；先按脚本准备 `out/memory-device/` 五个组件。分阶段执行：

```bash
python3 scripts/test-memory-device.py --serial emulator-5634 --phase install
python3 scripts/test-memory-device.py --serial emulator-5634 --phase storage
python3 scripts/test-memory-device.py --serial emulator-5634 --phase capture
# 使用设备已有凭据，产生真实模型用量；不新建登录或读取认证缓存。
python3 scripts/test-memory-device.py --serial emulator-5634 --phase live --allow-live-model-calls
# 实际点击系统建议后继续核验，脚本不通过隐藏入口代替这次 UI 接受。
python3 scripts/test-memory-device.py --serial emulator-5634 --phase finish
python3 scripts/test-memory-device.py --serial emulator-5634 --phase ui
python3 scripts/test-memory-device.py --serial emulator-5634 --phase restore
```

[2026-10-22 模拟器验收记录](../../docs/reviews/taku-memory-2026-10-22/README.md)包含 32 项存储／权限回归、自动采集边界、真实模型闭环与实际 UI 截图；组件构建和静态检查不替代设备运行。首版不扩展为物理手机、完整 ROM、冷启动、全天候耗电或任意 App 内容覆盖的通过结论。

## 批分析升级验收

[0.2.0 模拟器验收](../../docs/reviews/taku-memory-batches-2026-10-22/README.md)记录原地升级、独立开关、限流补分析、真实 Jev 批处理与 UI。`scripts/test-memory-batch-device.py` 使用实际合成页面，不向生产队列注入正文；`capture` 验证关闭分析和进程中断，`live --allow-live-model-calls` 使用已有真实服务处理队列，`ui` 验证开关，`restore` 恢复原配置。测试会唤醒系统 JobService 以缩短模拟器等待，不绕过实际合并窗口、预算或队列。

隔离测试 APK 另提供 `org.agentos.memory.QueueTests`，使用实际生产 SQLite 实现，在不同签名的测试 UID 中运行假时钟和合成答案；不替代真实 Jev 验收，也不暴露生产时钟或答案注入接口。

## 0.3.0 原生界面

[界面设计与模拟器验收](../../docs/reviews/taku-memory-ui-2026-10-22/README.md)说明小布记忆等产品的参考依据，并提供首页、详情、筛选、建议和设置的模拟器运行截图。

- 底部导航分为记忆、建议、设置。首页固定搜索与分类，卡片按日期分组，保留真实来源、时间和分类状态；没有伪造截图缩略图、AI 摘要或语义搜索。
- 关键词、分类和来源可组合；应用内浏览支持加载后续记录。Agent 签名接口仍最多返回 20 条，接口与权限没有扩大。建议历史也能找到最近 20 条之外的已接受任务。
- 详情可以选择复制文字；来源等信息折叠展示，纠正保留历史，删除需确认。返回列表保留搜索、筛选和滚动位置。记录更新时不重建正在输入的搜索框。
- 自动记录、模型分析、Agent 查询、主动建议继续独立控制。设置按用途分组，队列、合并频率、服务诊断放在「运行详情」。

复测：先把两个构建产物放入 `out/memory-ui-device/`，恢复指定 AVD 运行，再执行：

```bash
python3 scripts/test-memory-ui-device.py --serial emulator-5634 --phase prepare --avd-initially-paused
python3 scripts/test-memory-ui-device.py --serial emulator-5634 --phase ui
python3 scripts/test-memory-ui-device.py --serial emulator-5634 --phase restore
```

`--avd-initially-paused` 仅在运行前确实暂停时使用，`restore` 会恢复暂停状态。验收要求模型分析已关闭，不产生真实 Jev 请求；合成页面经实际观察服务采集，未向生产数据库注入样例。脚本保留已有记录与测试合成记录，不重装清空应用。物理手机与完整 ROM 的界面验收仍待完成。

English: 0.3.0 adds native bottom navigation, a searchable and filterable chronological library, paginated history, a dedicated reading page, and grouped settings. Capture, model analysis, Agent access, and suggestions remain independent. The signed Agent API retains its existing limits. UI validation uses synthetic pages captured through the real observation service, with model analysis disabled; physical devices and full ROM integration are not covered.

## 0.3.1 品牌与真实浏览场景

首页、建议、设置和详情栏使用仓库现有 Taku 图形 Logo，强调色与 Logo 的品牌橙保持一致。网页记录使用可访问树实际提供且已包含在受保护采集文字中的文档标题；缺少文档标题时保留原窗口标题。该处理不扩展正文采集范围，也不请求模型生成标题。卡片优先摘录标题后的正文段落，完整采集文字保留在详情。

[品牌与真实场景验收](../../docs/reviews/taku-memory-brand-2026-10-22/README.md)使用实际 Firefox 浏览公开的 Taku Harness 文档、Android 开发资料和大都会博物馆来访信息，由生产观察服务自动形成记忆。截图使用真实应用图标、页面标题、来源和采集时间。Firefox 仅是开发 AVD 的验收浏览器，不是记忆 APK 的运行依赖。

复测入口为 `scripts/test-memory-real-browser-device.py`。需先保存在 `out/memory-brand-device/baseline.json` 中的原配置及暂停状态，并在指定 AVD 安装 Firefox；分 `capture`、`ui`、`restore` 阶段执行。网页加载及浏览器提示可能随站点和版本变化，先确认实际页面已正常显示。此流程保持模型分析关闭，结束后恢复观察配置与 AVD 暂停状态，保留历史及本轮公开页面记录。

English: 0.3.1 incorporates the existing Taku logo and brand orange. Browser memories use the actual accessible document title already present in captured evidence, with the window title as a fallback. Cards prefer a source paragraph; full captured text remains available. Emulator acceptance uses actual Firefox visits to public project, development, and museum pages, with model analysis disabled. Physical devices and full ROM integration remain outside this acceptance.

## 0.4.0 中英文与日常 App

默认资源为英文，提供完整中文资源；在「Settings → Language / 设置 → 语言」选择跟随系统、English 或中文，也可使用 Android 的应用语言设置。切换会重建原生界面，保留筛选、记录、原文、分类、队列及模型配置。分类的显示名会翻译，既有数据库分类值保持兼容，用户原文不被翻译或改写。轻提示正文跟随记忆 App 语言；系统展示宿主的通用按钮和辅助说明跟随宿主语言。

接受建议时向现有 Broker 传递有限的语言偏好，新增任务用对应语言回复；旧任务结果保持原文。结果页用同一份固定版本 CommonMark 依赖渲染标题、强调和列表，显示原文 HTML 字面量、不加载远程图像、不执行或跟随模型链接；许可证包含在 APK 的 assets/licenses。

[日常 App 验收与英文截图](../../docs/reviews/taku-memory-daily-apps-2026-10-22/README.md)使用实际 Android Messaging、Fossify Calendar 和 Organic Maps。聊天为模拟器接收的演示短信，日程为本地演示事件，地图为真实离线地图计算的步行路线；不是私人会话、真实公司日程或预订。

- 聊天和日程：自动记录 → 手机内 Magpie / 真实 Jev → 英文轻提示 → 实际点按 → 原 TaskBroker / Agent → 成功调用对应记忆的 memory_get，英文结果已完成。
- 路线：自动记录和 Travel 分类通过；Jev 返回 none、置信度 0.69，保持安静，没有提示或任务。不会为展示效果强行生成建议。
- 中英文往返切换、66 项存储、69 项队列、8 项结果渲染检查通过。测试 APK 使用独立 UID 和数据库。

复测脚本为 `scripts/test-memory-daily-device.py`。先在专用 AVD 实际 App 中准备演示内容，记录原配置，再分阶段运行 capture、classify、finish；classify 必须显式传入 --allow-live-model-calls。无建议的记录用 no-offer 验证。需要在 UIAutomator 检查后重新连接观察服务，并通过实际页面操作产生采集事件。脚本不注入记忆、不伪造模型回答，也不代替用户点击创建任务。

本轮仍为模拟器组件验收；0.4.0 未获同版真机验收，独立记忆 APK 尚未接入产品 ROM 构建。意外退出后的数据恢复只证明本次专用模拟器状态保留，不扩大为真机、完整 ROM 或长期稳定性结论。

当前系统 AI 联动已在 Pixel 9 / Android 17 完成真实模型验收，版本、安装哈希、截图和整机重启边界见 [2026-10-23 验收记录](../../docs/reviews/system-ai-2026-10-23/README.md)。
