<div align="center">
<h1>SolonCode</h1>
<p>เอเจนต์การเขียนโค้ดโอเพ่นซอร์สที่สร้างด้วย <a href="https://github.com/opensolon/solon-ai">Solon AI</a> และ Java (รองรับสภาพแวดล้อมรันไทม์ Java8 ถึง Java26)</p>
<p>เวอร์ชันล่าสุด: v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="พื้นที่ทำงาน SolonCode Desktop" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## การติดตั้งและการตั้งค่า

การติดตั้ง:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

การตั้งค่า (แนะนำให้ผู้ใช้ใหม่ตั้งค่าผ่านหน้าเว็บตั้งค่าก่อน):

```
soloncode web 0
```

เมื่อเข้าสู่หน้าแล้ว ให้เปิด "การตั้งค่า -> โมเดลภาษาขนาดใหญ่ (LLM)" เพิ่มโมเดลและทดสอบการเชื่อมต่อ

<img height="260" src="SETTINGS-LLM.png">

## การทำงาน

รันคำสั่ง `soloncode cli` (CLI แบบโต้ตอบ) หรือ `soloncode web 0` (Web แบบโต้ตอบ) จากไดเรกทอรีใดก็ได้ในคอนโซล (กล่าวคือ พื้นที่ทำงานของคุณ)

* `soloncode cli` (CLI แบบโต้ตอบ)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web แบบโต้ตอบ)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

การทดสอบฟีเจอร์ (ลองใช้งานงานต่อไปนี้ จากง่ายไปยาก):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // แนะนำให้ติดตั้งสกิลบางอย่างล่วงหน้า
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## SolonCode Desktop

SolonCode Desktop คือประสบการณ์ IDE บนเครื่องของ SolonCode ซึ่งรวมการสนทนากับ Agent ไฟล์โปรเจกต์ ตัวแก้ไข Monaco เทอร์มินัลในตัว การเปลี่ยนแปลง Git และการรันงานไว้ใน workspace เดียว ตัวไคลเอนต์สร้างด้วย Tauri, React และ TypeScript ส่วน backend Java CLI ให้บริการ Agent runtime การเข้าถึงโมเดล และเครื่องมือ

คุณสมบัติเด่น:

* **โหมด Agent** — รันแบบต้องอนุมัติ แก้ไขอัตโนมัติ วางแผนแบบอ่านอย่างเดียว และรัน Goal อย่างต่อเนื่อง
* **การสนทนาตามบริบทโปรเจกต์** — แนบรูปภาพและไฟล์ ใช้บริบท workspace เพิ่มงานระหว่างที่กำลังรัน และแสดงสถิติโมเดล Token และเวลา
* **เซสชันที่เชื่อถือได้** — เก็บประวัติ หน่วยความจำระยะยาว ย้อนกลับ รันใหม่ ลบอย่างปลอดภัย และ checkpoint ของ workspace
* **เครื่องมือพัฒนาในตัว** — ไฟล์ ตัวแก้ไข เทอร์มินัล Git รายการงาน Skills, Agents, MCP, OpenAPI, LSP และระบบอัตโนมัติ

เมื่อรันจากซอร์สโค้ด ให้เริ่ม backend แยกต่างหาก:

~~~bash
# เทอร์มินัล 1: Desktop backend
soloncode serve 4808

# เทอร์มินัล 2: Desktop client
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

โหมดพัฒนาเชื่อมต่อพอร์ต 4808 และจะไม่เริ่มหรือตรวจจับโปรเซส backend โดยอัตโนมัติ ดูรายละเอียดที่ [Desktop README](soloncode-desktop/README.md) และ [คู่มือเริ่มต้น SolonCode Desk](docs/soloncode-desk-getting-started.md)

## เอกสาร

สำหรับรายละเอียดการตั้งค่าเพิ่มเติม โปรดเยี่ยมชม [เอกสารอย่างเป็นทางการ](https://solon.noear.org/article/soloncode)

## มีส่วนร่วม

หากคุณสนใจที่จะมีส่วนร่วมในการพัฒนาโค้ด โปรดอ่าน [เอกสารการมีส่วนร่วม](https://solon.noear.org/article/623) ก่อนส่ง PR

## การพัฒนาบนพื้นฐาน SolonCode

หากคุณใช้ "soloncode" ในชื่อโปรเจกต์ของคุณ (เช่น "soloncode-dashboard" หรือ "soloncode-app") โปรดระบุใน README ว่าโปรเจกต์นี้ไม่ได้พัฒนาโดยทีม OpenSolon อย่างเป็นทางการและไม่มีความเกี่ยวข้อง

## คำถามที่พบบ่อย: แตกต่างจาก Claude Code อย่างไร?

ในแง่การทำงานนั้นคล้ายคลึงกัน โดยมีความแตกต่างหลักดังนี้:

* สร้างด้วย Java โอเพ่นซอร์ส 100% รองรับ BiSheng JDK (Huawei) และ Harmony PC
* ขับเคลื่อนและสร้างด้วยพรอมต์ภาษาจีนล้วน
* ไม่ขึ้นกับผู้ให้บริการ กำหนดค่าโมเดลตามต้องการ การพัฒนาโมเดลจะช่วยลดช่องว่างและลดต้นทุน ทำให้การกำหนดค่าอย่างอิสระเป็นสิ่งสำคัญ
* รองรับพร้อมกันทั้งอินเทอร์เฟซบรรทัดคำสั่งเทอร์มินัล (CLI), อินเทอร์เฟซเบราว์เซอร์ (WEB) และอินเทอร์เฟซ IDE บนเดสก์ท็อป (Desktop)
* รองรับ Web และโปรโตคอล ACP สำหรับการสื่อสารระยะไกล
