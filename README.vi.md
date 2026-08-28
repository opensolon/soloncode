<div align="center">
<h1>SolonCode</h1>
<p>SolonCode là một tác nhân mã hóa mã nguồn mở được xây dựng dựa trên <a href="https://github.com/opensolon/solon-ai">Solon AI</a> và Java, hỗ trợ môi trường từ Java8 đến Java26.</p>
<p>Phiên bản mới nhất: v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="Không gian làm việc SolonCode Desktop" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Cài đặt và Cấu hình

Cài đặt:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Cấu hình (người dùng mới được khuyến nghị cấu hình trước qua trang cài đặt Web):

```
soloncode web 0
```

Sau khi vào trang, mở "Cài đặt -> Mô hình ngôn ngữ lớn (LLM)", thêm mô hình và kiểm tra kết nối.

<img height="260" src="SETTINGS-LLM.png">

## Chạy

Chạy lệnh `soloncode cli` (CLI tương tác) hoặc `soloncode web 0` (Web tương tác) từ bất kỳ thư mục nào trong bảng điều khiển (tức là không gian làm việc của bạn).

* `soloncode cli` (CLI tương tác)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web tương tác)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

Kiểm tra Tính năng (thử các tác vụ sau, từ đơn giản đến phức tạp):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Khuyên dùng nên cài đặt một số kỹ năng trước
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## SolonCode Desktop

SolonCode Desktop là trải nghiệm IDE cục bộ của SolonCode. Ứng dụng tập hợp hội thoại với Agent, tệp dự án, trình soạn thảo Monaco, terminal tích hợp, thay đổi Git và thực thi tác vụ trong một workspace. Client được xây dựng bằng Tauri, React và TypeScript; backend CLI Java cung cấp runtime Agent, truy cập mô hình và công cụ.

Tính năng nổi bật:

* **Chế độ Agent** — thực thi có phê duyệt, chỉnh sửa tự động, lập kế hoạch chỉ đọc và thực thi Goal liên tục.
* **Hội thoại theo dự án** — đính kèm hình ảnh và tệp, ngữ cảnh workspace, thêm tác vụ khi đang chạy và thống kê mô hình, Token, thời gian.
* **Phiên làm việc tin cậy** — lịch sử bền vững, bộ nhớ dài hạn, quay lui, chạy lại, xóa an toàn và checkpoint workspace.
* **Công cụ phát triển tích hợp** — tệp, trình soạn thảo, terminal, Git, danh sách tác vụ, Skills, Agents, MCP, OpenAPI, LSP và tự động hóa.

Khi chạy từ mã nguồn, hãy khởi động backend riêng:

~~~bash
# Terminal 1: backend desktop
soloncode serve 4808

# Terminal 2: client desktop
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

Chế độ phát triển kết nối tới cổng 4808 và không tự động khởi động hoặc dò tìm tiến trình backend. Xem [Desktop README](soloncode-desktop/README.md) và [hướng dẫn bắt đầu với SolonCode Desk](docs/soloncode-desk-getting-started.md).

## Tài liệu

Để biết thêm chi tiết cấu hình, vui lòng truy cập [Tài liệu Chính thức](https://solon.noear.org/article/soloncode) của chúng tôi.

## Đóng góp

Nếu bạn quan tâm đến việc đóng góp mã, vui lòng đọc [Tài liệu Đóng góp](https://solon.noear.org/article/623) trước khi gửi PR.

## Phát triển Dựa trên SolonCode

Nếu bạn sử dụng "soloncode" trong tên dự án của mình (ví dụ: "soloncode-dashboard" hoặc "soloncode-app"), vui lòng ghi chú trong README rằng dự án không được phát triển chính thức bởi đội ngũ OpenSolon và không có sự liên kết.

## Câu hỏi thường gặp: Sự khác biệt với Claude Code là gì?

Về mặt chức năng, chúng tương tự nhau, với các điểm khác biệt chính:

* Được xây dựng bằng Java, 100% mã nguồn mở. Tương thích với BiSheng JDK (Huawei) và Harmony PC.
* Hoàn toàn được điều khiển và xây dựng bằng prompt tiếng Trung
* Không phụ thuộc vào nhà cung cấp. Cấu hình mô hình theo nhu cầu. Việc lặp lại mô hình sẽ thu hẹp khoảng cách và giảm chi phí, khiến cấu hình linh hoạt trở nên quan trọng.
* Hỗ trợ đồng thời giao diện dòng lệnh terminal (CLI), giao diện trình duyệt (WEB) và giao diện IDE máy tính để bàn (Desktop).
* Hỗ trợ Web, giao thức ACP để giao tiếp từ xa.
