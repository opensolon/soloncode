<div align="center">
<h1>SolonCode</h1>
<p>SolonCode là một tác nhân mã hóa mã nguồn mở được xây dựng dựa trên <a href="https://github.com/opensolon/solon-ai">Solon AI</a> và Java, hỗ trợ môi trường từ Java8 đến Java26.</p>
<p>Phiên bản mới nhất: v2026.5.2</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Cài đặt và Cấu hình

Cài đặt:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Cấu hình (bắt buộc phải sửa sau khi cài đặt):

* Thư mục cài đặt: `~/soloncode/bin/`
* Tìm tệp cấu hình `~/soloncode/config.yml` và sửa cấu hình `models` (chủ yếu)
* Đối với các tùy chọn cấu hình `models`, tham khảo: [Cấu hình Mô hình và Tùy chọn Yêu cầu](https://solon.noear.org/article/1087)

## Chạy

Chạy lệnh `soloncode` từ bất kỳ thư mục nào trong bảng điều khiển (tức là không gian làm việc của bạn).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.5.2
/Users/noear
Tips: (esc) interrupt | /(tab) ls commands

User
> 
```

Kiểm tra Tính năng (thử các tác vụ sau, từ đơn giản đến phức tạp):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Khuyên dùng nên cài đặt một số kỹ năng trước
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## Tài liệu

Để biết thêm chi tiết cấu hình, vui lòng truy cập [Tài liệu Chính thức](https://solon.noear.org/article/soloncode) của chúng tôi.

## Đóng góp

Nếu bạn quan tâm đến việc đóng góp mã, vui lòng đọc [Tài liệu Đóng góp](https://solon.noear.org/article/623) trước khi gửi PR.

## Phát triển Dựa trên SolonCode

Nếu bạn sử dụng "soloncode" trong tên dự án của mình (ví dụ: "soloncode-dashboard" hoặc "soloncode-app"), vui lòng ghi chú trong README rằng dự án không được phát triển chính thức bởi đội ngũ OpenSolon và không có sự liên kết.

## Câu hỏi thường gặp: Sự khác biệt với Claude Code và OpenCode là gì?

Về mặt chức năng, chúng tương tự nhau, với các điểm khác biệt chính:

* Được xây dựng bằng Java, 100% mã nguồn mở.
* Hoàn toàn được điều khiển và xây dựng bằng prompt tiếng Trung
* Không phụ thuộc vào nhà cung cấp. Yêu cầu cấu hình mô hình. Việc lặp lại mô hình sẽ thu hẹp khoảng cách và giảm chi phí, khiến phương pháp không phụ thuộc nhà cung cấp trở nên quan trọng.
* Tập trung vào giao diện dòng lệnh terminal (CLI), chạy thông qua lệnh hệ thống.
* Hỗ trợ Web, giao thức ACP để giao tiếp từ xa.