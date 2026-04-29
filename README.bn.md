<div align="center">
<h1>SolonCode</h1>
<p><a href="https://github.com/opensolon/solon-ai">Solon AI</a> এবং জাভা দিয়ে তৈরি একটি ওপেন-সোর্স কোডিং এজেন্ট (Java8 থেকে Java26 রানটাইম পরিবেশ সমর্থিত)</p>
<p>সর্বশেষ সংস্করণ: v2026.4.30</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## ইনস্টলেশন এবং কনফিগারেশন

ইনস্টলেশন:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

কনফিগারেশন (ইনস্টলেশনের পর অবশ্যই পরিবর্তন করতে হবে):

* ইনস্টলেশন ডিরেক্টরি: `~/soloncode/bin/`
* `~/soloncode/config.yml` কনফিগারেশন ফাইলটি খুঁজে বের করুন এবং `models` কনফিগারেশন পরিবর্তন করুন (প্রধানত)
* `models` কনফিগারেশন বিকল্পের জন্য, দেখুন: [মডেল কনফিগারেশন এবং অনুরোধ বিকল্প](https://solon.noear.org/article/1087)

## চলমান

কনসোলে যেকোনো ডিরেক্টরি থেকে `soloncode` কমান্ড চালান (অর্থাৎ, আপনার ওয়ার্কস্পেস)।

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.4.30
/Users/noear
Tips: (esc) interrupt | '/exit' | '/resume' | '/clear' | '/model'

User
> 
```

বৈশিষ্ট্য পরীক্ষা (নিম্নলিখিত কাজগুলো চেষ্টা করুন, সহজ থেকে জটিল):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // আগে কিছু দক্ষতা ইনস্টল করার পরামর্শ দেওয়া হয়
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## ডকুমেন্টেশন

আরও কনফিগারেশন বিস্তারিত জানতে, আমাদের [অফিসিয়াল ডকুমেন্টেশন](https://solon.noear.org/article/soloncode) দেখুন।

## অবদান

আপনি যদি কোড অবদানে আগ্রহী হন, তাহলে PR জমা দেওয়ার আগে [অবদান নথিপত্র](https://solon.noear.org/article/623) পড়ুন।

## SolonCode এর উপর ভিত্তি করে ডেভেলপমেন্ট

আপনি যদি আপনার প্রকল্পের নামে "soloncode" ব্যবহার করেন (যেমন "soloncode-dashboard" বা "soloncode-app"), তাহলে README-তে উল্লেখ করুন যে প্রকল্পটি OpenSolon টিম দ্বারা আনুষ্ঠানিকভাবে তৈরি নয় এবং এর কোনো সম্পর্ক নেই।

## সচরাচর জিজ্ঞাসা: Claude Code এবং OpenCode থেকে পার্থক্য কী?

এগুলো কার্যক্ষমতার দিক থেকে অনুরূপ, মূল পার্থক্যগুলো হলো:

* জাভা দিয়ে তৈরি, ১০০% ওপেন-সোর্স।
* প্রোভাইডার-অজ্ঞেয়বাদী। মডেল কনফিগারেশন প্রয়োজন। মডেল পুনরাবৃত্তি ব্যবধান কমাবে এবং খরচ কমাবে, যা প্রোভাইডার-অজ্ঞেয়বাদী পদ্ধতিকে গুরুত্বপূর্ণ করে তোলে।
* টার্মিনাল কমান্ড-লাইন ইন্টারফেস (CLI) এ ফোকাস করা, সিস্টেম কমান্ডের মাধ্যমে চালানো হয়।
* ওয়েব সমর্থন করে, দূরবর্তী যোগাযোগের জন্য ACP প্রোটোকল।