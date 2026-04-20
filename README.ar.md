<div dir="rtl" align="right">
<h1>SolonCode</h1>
<p>وكيل برمجة مفتوح المصدر مبني باستخدام <a href="https://github.com/opensolon/solon-ai">Solon AI</a> وجافا (يدعم بيئات تشغيل Java8 إلى Java26)</p>
<p>أحدث إصدار: v2026.4.22</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

<div dir="rtl" align="right">

## التثبيت والإعداد

التثبيت:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

الإعداد (يجب تعديله بعد التثبيت):

* دليل التثبيت: `~/soloncode/bin/`
* حدد ملف التكوين `~/soloncode/config.yml` وقم بتعديل تكوين `models` (بشكل أساسي)
* للاطلاع على خيارات تكوين `models`، راجع: [تكوين النموذج وخيارات الطلب](https://solon.noear.org/article/1087)

## التشغيل

قم بتشغيل الأمر `soloncode` من أي دليل في وحدة التحكم (أي مساحة العمل الخاصة بك).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.4.22
/Users/noear
Tips: (esc) interrupt | '/exit': quit | '/resume': resume | '/clear': reset

User
> 
```

اختبار الميزات (جرب المهام التالية، من البسيط إلى المعقد):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // يُنصح بتثبيت بعض المهارات مسبقًا
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## التوثيق

لمزيد من تفاصيل التكوين، يرجى زيارة [التوثيق الرسمي](https://solon.noear.org/article/soloncode).

## المساهمة

إذا كنت مهتمًا بالمساهمة في الكود، يرجى قراءة [وثائق المساهمة](https://solon.noear.org/article/623) قبل تقديم طلب سحب (PR).

## التطوير باستخدام SolonCode

إذا استخدمت "soloncode" في اسم مشروعك (مثل "soloncode-dashboard" أو "soloncode-app")، يرجى توضيح في ملف README أن المشروع لم يتم تطويره رسميًا من قبل فريق OpenSolon وليس له أي ارتباط به.

## الأسئلة الشائعة: ما الفرق عن Claude Code و OpenCode؟

متشابهة وظيفيًا، مع اختلافات رئيسية:

* مبني باستخدام جافا، مفتوح المصدر بالكامل 100%.
* يعتمد ويُبنى بالكامل على المطالبات باللغة الصينية
* مستقل عن مزود الخدمة. يتطلب تكوين النموذج. سيؤدي تطور النماذج إلى تضييق الفجوات وتقليل التكاليف، مما يجعل نهج الاستقلالية عن المزود أمرًا مهمًا.
* يركز على واجهة سطر الأوامر الطرفية (CLI)، ويعمل عبر أوامر النظام.
* يدعم الويب وبروتوكول ACP للاتصال عن بُعد.

</div>