<div align="center">
<h1>SolonCode</h1>
<p>เอเจนต์การเขียนโค้ดโอเพ่นซอร์สที่สร้างด้วย <a href="https://github.com/opensolon/solon-ai">Solon AI</a> และ Java (รองรับสภาพแวดล้อมรันไทม์ Java8 ถึง Java26)</p>
<p>เวอร์ชันล่าสุด: v2026.5.2</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## การติดตั้งและการตั้งค่า

การติดตั้ง:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

การตั้งค่า (ต้องแก้ไขหลังจากการติดตั้ง):

* ไดเรกทอรีการติดตั้ง: `~/soloncode/bin/`
* ค้นหาไฟล์การตั้งค่า `~/soloncode/config.yml` และแก้ไขการตั้งค่า `models` (หลัก)
* สำหรับตัวเลือกการตั้งค่า `models` โปรดดูที่: [การตั้งค่าโมเดลและตัวเลือกคำขอ](https://solon.noear.org/article/1087)

## การทำงาน

รันคำสั่ง `soloncode` จากไดเรกทอรีใดก็ได้ในคอนโซล (กล่าวคือ พื้นที่ทำงานของคุณ)

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.5.2
/Users/noear
Tips: (esc) interrupt | '/exit' | '/resume' | '/clear' | '/model'

User
> 
```

การทดสอบฟีเจอร์ (ลองใช้งานงานต่อไปนี้ จากง่ายไปยาก):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // แนะนำให้ติดตั้งสกิลบางอย่างล่วงหน้า
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## เอกสาร

สำหรับรายละเอียดการตั้งค่าเพิ่มเติม โปรดเยี่ยมชม [เอกสารอย่างเป็นทางการ](https://solon.noear.org/article/soloncode)

## มีส่วนร่วม

หากคุณสนใจที่จะมีส่วนร่วมในการพัฒนาโค้ด โปรดอ่าน [เอกสารการมีส่วนร่วม](https://solon.noear.org/article/623) ก่อนส่ง PR

## การพัฒนาบนพื้นฐาน SolonCode

หากคุณใช้ "soloncode" ในชื่อโปรเจกต์ของคุณ (เช่น "soloncode-dashboard" หรือ "soloncode-app") โปรดระบุใน README ว่าโปรเจกต์นี้ไม่ได้พัฒนาโดยทีม OpenSolon อย่างเป็นทางการและไม่มีความเกี่ยวข้อง

## คำถามที่พบบ่อย: แตกต่างจาก Claude Code และ OpenCode อย่างไร?

ในแง่การทำงานนั้นคล้ายคลึงกัน โดยมีความแตกต่างหลักดังนี้:

* สร้างด้วย Java โอเพ่นซอร์ส 100%
* ขับเคลื่อนและสร้างด้วยพรอมต์ภาษาจีนล้วน
* ไม่ขึ้นกับผู้ให้บริการ ต้องการการตั้งค่าโมเดล การพัฒนาโมเดลจะช่วยลดช่องว่างและลดต้นทุน ทำให้การไม่ขึ้นกับผู้ให้บริการเป็นสิ่งสำคัญ
* เน้นที่อินเทอร์เฟซบรรทัดคำสั่งเทอร์มินัล (CLI) รันผ่านคำสั่งระบบ
* รองรับ Web และโปรโตคอล ACP สำหรับการสื่อสารระยะไกล