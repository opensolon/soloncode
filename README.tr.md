<div align="center">
<h1>SolonCode</h1>
<p><a href="https://github.com/opensolon/solon-ai">Solon AI</a> ve Java ile oluşturulmuş açık kaynaklı bir kodlama ajanıdır (Java8'den Java26'ya kadar olan çalışma ortamlarını destekler)</p>
<p>En Son Sürüm: v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="SolonCode Desktop çalışma alanı" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Kurulum ve Yapılandırma

Kurulum:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Yapılandırma (yeni kullanıcıların önce Web ayarları sayfası üzerinden yapılandırma yapması önerilir):

```
soloncode web 0
```

Sayfaya girdikten sonra "Ayarlar -> Büyük Dil Modeli (LLM)" bölümünü açın, bir model ekleyin ve bağlantıyı test edin.

<img height="260" src="SETTINGS-LLM.png">

## Çalıştırma

Konsolda herhangi bir dizinden `soloncode cli` (CLI etkileşimli) veya `soloncode web 0` (Web etkileşimli) komutunu çalıştırın (yani çalışma alanınızdan).

* `soloncode cli` (CLI etkileşimli)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web etkileşimli)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

Özellik Testi (basitten karmaşığa aşağıdaki görevleri deneyin):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Önceden bazı beceriler kurmanız önerilir
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## SolonCode Desktop

SolonCode Desktop, SolonCode'un yerel IDE deneyimidir. Agent konuşmalarını, proje dosyalarını, Monaco düzenleyicisini, entegre terminali, Git değişikliklerini ve görev yürütmeyi tek bir çalışma alanında birleştirir. İstemci Tauri, React ve TypeScript ile geliştirilmiştir; Java CLI backend ise Agent çalışma zamanını, model erişimini ve araçları sağlar.

Öne çıkan özellikler:

* **Agent modları** — onaylı yürütme, otomatik düzenleme, salt okunur planlama ve sürekli Goal yürütme.
* **Projeye bağlı konuşmalar** — görüntü ve dosya ekleri, çalışma alanı bağlamı, çalışma sırasında yeni görevler ve model, Token, süre istatistikleri.
* **Güvenilir oturumlar** — kalıcı geçmiş, uzun süreli bellek, geri alma, yeniden çalıştırma, güvenli silme ve kontrol noktaları.
* **Entegre geliştirme araçları** — dosyalar, düzenleyici, terminal, Git, görev listesi, Skills, Agents, MCP, OpenAPI, LSP ve otomasyonlar.

Kaynak koddan çalıştırırken backend'i ayrı başlatın:

~~~bash
# Terminal 1: Desktop backend
soloncode serve 4808

# Terminal 2: Desktop istemcisi
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

Geliştirme modu 4808 portuna bağlanır ve backend sürecini otomatik olarak başlatmaz veya algılamaz. Ayrıntılar için [Desktop README](soloncode-desktop/README.md) ve [SolonCode Desk başlangıç kılavuzuna](docs/soloncode-desk-getting-started.md) bakın.

## Dokümantasyon

Daha fazla yapılandırma detayı için [Resmi Dokümantasyon](https://solon.noear.org/article/soloncode) sayfamızı ziyaret edin.

## Katkıda Bulunma

Katkıda bulunmak istiyorsanız, PR göndermeden önce lütfen [Katkı Dokümantasyonu](https://solon.noear.org/article/623)'nu okuyun.

## SolonCode Tabanlı Geliştirme

Proje adınızda "soloncode" kullanıyorsanız (örneğin "soloncode-dashboard" veya "soloncode-app"), README'de projenin OpenSolon ekibi tarafından resmi olarak geliştirilmediğini ve herhangi bir bağlantısı olmadığını belirtmeniz gerekir.

## Sıkça Sorulan Sorular: Claude Code'dan farkları nelerdir?

İşlevsel olarak benzerdirler, temel farklar şunlardır:

* Java ile oluşturulmuş, %100 açık kaynaklıdır. BiSheng JDK (Huawei) ve Harmony PC ile uyumludur.
* Tamamen Çince promptlarla yönetilir ve oluşturulur
* Sağlayıcıdan bağımsızdır. Modelleri ihtiyaca göre yapılandırın. Model yinelemeleri boşlukları daraltacak ve maliyetleri azaltacaktır, bu da esnek yapılandırmayı önemli kılar.
* Aynı anda terminal komut satırı arayüzünü (CLI), tarayıcı arayüzünü (WEB) ve masaüstü IDE arayüzünü (Desktop) destekler.
* Web, uzaktan iletişim için ACP protokolünü destekler.
