<div align="center">
<h1>SolonCode</h1>
<p><a href="https://github.com/opensolon/solon-ai">Solon AI</a> ve Java ile oluşturulmuş açık kaynaklı bir kodlama ajanıdır (Java8'den Java26'ya kadar olan çalışma ortamlarını destekler)</p>
<p>En Son Sürüm: v2026.5.5</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Kurulum ve Yapılandırma

Kurulum:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Yapılandırma (kurulumdan sonra mutlaka düzenlenmelidir):

* Kurulum dizini: `~/soloncode/bin/`
* `~/soloncode/config.yml` yapılandırma dosyasını bulup `models` yapılandırmasını düzenleyin (öncelikle)
* `models` yapılandırma seçenekleri için bkz: [Model Yapılandırması ve İstek Seçenekleri](https://solon.noear.org/article/1087)

## Çalıştırma

Konsolda herhangi bir dizinden `soloncode` komutunu çalıştırın (yani çalışma alanınızdan).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.5.5
/Users/noear
Tips: (esc) interrupt | /(tab) ls command | @(tab) ls agent

User
> 
```

Özellik Testi (basitten karmaşığa aşağıdaki görevleri deneyin):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Önceden bazı beceriler kurmanız önerilir
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## Dokümantasyon

Daha fazla yapılandırma detayı için [Resmi Dokümantasyon](https://solon.noear.org/article/soloncode) sayfamızı ziyaret edin.

## Katkıda Bulunma

Katkıda bulunmak istiyorsanız, PR göndermeden önce lütfen [Katkı Dokümantasyonu](https://solon.noear.org/article/623)'nu okuyun.

## SolonCode Tabanlı Geliştirme

Proje adınızda "soloncode" kullanıyorsanız (örneğin "soloncode-dashboard" veya "soloncode-app"), README'de projenin OpenSolon ekibi tarafından resmi olarak geliştirilmediğini ve herhangi bir bağlantısı olmadığını belirtmeniz gerekir.

## Sıkça Sorulan Sorular: Claude Code'dan farkları nelerdir?

İşlevsel olarak benzerdirler, temel farklar şunlardır:

* Java ile oluşturulmuş, %100 açık kaynaklıdır.
* Tamamen Çince promptlarla yönetilir ve oluşturulur
* Sağlayıcıdan bağımsızdır. Modelleri ihtiyaca göre yapılandırın. Model yinelemeleri boşlukları daraltacak ve maliyetleri azaltacaktır, bu da esnek yapılandırmayı önemli kılar.
* Aynı anda terminal komut satırı arayüzünü (CLI), tarayıcı arayüzünü (WEB) ve masaüstü IDE arayüzünü (Desktop) destekler.
* Web, uzaktan iletişim için ACP protokolünü destekler.