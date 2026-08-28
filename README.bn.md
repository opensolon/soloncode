<div align="center">
<h1>SolonCode</h1>
<p><a href="https://github.com/opensolon/solon-ai">Solon AI</a> এবং জাভা দিয়ে তৈরি একটি ওপেন-সোর্স কোডিং এজেন্ট (Java8 থেকে Java26 রানটাইম পরিবেশ সমর্থিত)</p>
<p>সর্বশেষ সংস্করণ: v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="SolonCode Desktop workspace" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## ইনস্টলেশন এবং কনফিগারেশন

ইনস্টলেশন:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

কনফিগারেশন (নতুন ব্যবহারকারীদের প্রথমে ওয়েব সেটিংস পৃষ্ঠা ব্যবহার করে কনফিগার করার পরামর্শ দেওয়া হয়):

```bash
soloncode web 0
```

পৃষ্ঠায় প্রবেশ করার পর "সেটিংস -> বড় ভাষার মডেল (LLM)" খুলুন, একটি মডেল যোগ করুন এবং সংযোগ পরীক্ষা করুন।

<img height="260" src="SETTINGS-LLM.png">

## চলমান

কনসোলে যেকোনো ডিরেক্টরি থেকে `soloncode cli` (CLI ইন্টারেক্টিভ) অথবা `soloncode web 0` (Web ইন্টারেক্টিভ) কমান্ড চালান (অর্থাৎ, আপনার ওয়ার্কস্পেস)।

* `soloncode` (CLI ইন্টারেক্টিভ)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web ইন্টারেক্টিভ)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

বৈশিষ্ট্য পরীক্ষা (নিম্নলিখিত কাজগুলো চেষ্টা করুন, সহজ থেকে জটিল):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // আগে কিছু দক্ষতা ইনস্টল করার পরামর্শ দেওয়া হয়
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## SolonCode Desktop

SolonCode Desktop হলো SolonCode-এর স্থানীয় IDE অভিজ্ঞতা। এটি Agent কথোপকথন, প্রকল্পের ফাইল, Monaco এডিটর, সমন্বিত টার্মিনাল, Git পরিবর্তন এবং টাস্ক নির্বাহকে একটি workspace-এ একত্র করে। ডেস্কটপ ক্লায়েন্টটি Tauri, React ও TypeScript দিয়ে তৈরি; Java CLI backend Agent runtime, মডেল অ্যাক্সেস ও টুল সরবরাহ করে।

প্রধান বৈশিষ্ট্য:

* **Agent মোড** — অনুমোদনসহ নির্বাহ, স্বয়ংক্রিয় সম্পাদনা, শুধু-পঠন পরিকল্পনা এবং ধারাবাহিক Goal নির্বাহ।
* **প্রকল্প-সচেতন কথোপকথন** — ছবি ও ফাইল সংযুক্তি, workspace প্রসঙ্গ, চলমান অবস্থায় নতুন টাস্ক এবং মডেল, Token ও সময়ের পরিসংখ্যান।
* **নির্ভরযোগ্য সেশন** — স্থায়ী ইতিহাস, দীর্ঘমেয়াদি মেমরি, রিওয়াইন্ড, পুনরায় চালানো, নিরাপদ মোছা এবং workspace checkpoint।
* **সমন্বিত ডেভেলপমেন্ট টুল** — ফাইল, এডিটর, টার্মিনাল, Git, টাস্ক তালিকা, Skills, Agents, MCP, OpenAPI, LSP ও automation।

সোর্স থেকে চালানোর সময় backend আলাদাভাবে চালু করুন:

~~~bash
# টার্মিনাল 1: Desktop backend
soloncode serve 4808

# টার্মিনাল 2: Desktop client
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

ডেভেলপমেন্ট মোড 4808 পোর্টের backend-এ সংযোগ করে এবং backend প্রক্রিয়া স্বয়ংক্রিয়ভাবে চালু বা শনাক্ত করে না। বিস্তারিত জানতে [Desktop README](soloncode-desktop/README.md) এবং [SolonCode Desk শুরুর নির্দেশিকা](docs/soloncode-desk-getting-started.md) দেখুন।

## ডকুমেন্টেশন

আরও কনফিগারেশন বিস্তারিত জানতে, আমাদের [অফিসিয়াল ডকুমেন্টেশন](https://solon.noear.org/article/soloncode) দেখুন।

## অবদান

আপনি যদি কোড অবদানে আগ্রহী হন, তাহলে PR জমা দেওয়ার আগে [অবদান নথিপত্র](https://solon.noear.org/article/623) পড়ুন।

## SolonCode এর উপর ভিত্তি করে ডেভেলপমেন্ট

আপনি যদি আপনার প্রকল্পের নামে "soloncode" ব্যবহার করেন (যেমন "soloncode-dashboard" বা "soloncode-app"), তাহলে README-তে উল্লেখ করুন যে প্রকল্পটি OpenSolon টিম দ্বারা আনুষ্ঠানিকভাবে তৈরি নয় এবং এর কোনো সম্পর্ক নেই।

## সচরাচর জিজ্ঞাসা: Claude Code থেকে পার্থক্য কী?

এগুলো কার্যক্ষমতার দিক থেকে অনুরূপ, মূল পার্থক্যগুলো হলো:

* জাভা দিয়ে তৈরি, ১০০% ওপেন-সোর্স। BiSheng JDK (Huawei) এবং Harmony PC এর সাথে সামঞ্জস্যপূর্ণ।
* সম্পূর্ণ চীনা প্রম্পট দ্বারা পরিচালিত এবং নির্মিত
* প্রোভাইডার-স্বাধীন। প্রয়োজন অনুযায়ী মডেল কনফিগার করুন। মডেল পুনরাবৃত্তি ব্যবধান কমাবে এবং খরচ কমাবে, যা নমনীয় কনফিগারেশনকে গুরুত্বপূর্ণ করে তোলে।
* একই সাথে টার্মিনাল কমান্ড-লাইন ইন্টারফেস (CLI), ব্রাউজার ইন্টারফেস (WEB) এবং ডেস্কটপ IDE ইন্টারফেস (Desktop) সমর্থন করে।
* ওয়েব সমর্থন করে, দূরবর্তী যোগাযোগের জন্য ACP প্রোটোকল।
