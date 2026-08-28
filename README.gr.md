<div align="center">
<h1>SolonCode</h1>
<p>Ένας ανοιχτού κώδικα πράκτορας κωδικοποίησης βασισμένος στο <a href="https://github.com/opensolon/solon-ai">Solon AI</a> και Java (υποστηρίζει περιβάλλοντα εκτέλεσης Java8 έως Java26)</p>
<p>Τελευταία Έκδοση: v2026.8.28</p>
<img height="260" src="SHOW_CLI.png" />
<img height="260" src="SHOW_WEB.png" />
<br />
<img height="260"  src="SHOW_DESKTOP.png" alt="Χώρος εργασίας SolonCode Desktop" />
</div>

<div align="center">

[中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Εγκατάσταση και διαμόρφωση

Εγκατάσταση:

```bash
# Mac / Linux / Harmony PC:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Διαμόρφωση (οι νέοι χρήστες συνιστάται να διαμορφώσουν πρώτα μέσω της σελίδας ρυθμίσεων Web):

```
soloncode web 0
```

Μόλις μπείτε στη σελίδα, ανοίξτε "Ρυθμίσεις -> LLM", προσθέστε ένα μοντέλο και ελέγξτε τη σύνδεση.

<img height="260" src="SETTINGS-LLM.png">

## Εκτέλεση

Εκτελέστε την εντολή `soloncode cli` (CLI διαδραστικό) ή `soloncode web 0` (Web διαδραστικό) από οποιονδήποτε κατάλογο στην κονσόλα (δηλαδή, τον χώρο εργασίας σας).

* `soloncode cli` (CLI διαδραστικό)

```bash
demo@MacBook-Pro ~ % soloncode cli
SolonCode v2026.8.28 PID-87950 Model:deepseek-v4-flash
/Users/demo
Tips: (esc) interrupt | /(tab) command | $(tab) skill | @(tab) agent

User
❯ 
```

* `soloncode web 0` (Web διαδραστικό)

```bash
demo@MacBook-Pro ~ % soloncode web 0
SolonCode v2026.8.28 PID-73617 Model:deepseek-v4-flash
/path/demo
2026-07-09 11:26
Web interface: http://localhost:50488/
```

Δοκιμή Λειτουργιών (δοκιμάστε τις παρακάτω εργασίες, από απλές σε σύνθετες):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Προτείνεται η προηγούμενη εγκατάσταση κάποιων δεξιοτήτων
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## SolonCode Desktop

Το SolonCode Desktop είναι η τοπική εμπειρία IDE του SolonCode. Συγκεντρώνει συνομιλίες Agent, αρχεία έργου, τον επεξεργαστή Monaco, ενσωματωμένο τερματικό, αλλαγές Git και εκτέλεση εργασιών σε έναν χώρο εργασίας. Ο client βασίζεται σε Tauri, React και TypeScript, ενώ το Java CLI backend παρέχει το Agent runtime, πρόσβαση στα μοντέλα και εργαλεία.

Κύριες δυνατότητες:

* **Λειτουργίες Agent** — εκτέλεση με έγκριση, αυτόματη επεξεργασία, σχεδιασμός μόνο για ανάγνωση και συνεχής εκτέλεση Goal.
* **Συνομιλίες με επίγνωση έργου** — εικόνες και αρχεία, περιβάλλον workspace, πρόσθετες εργασίες κατά την εκτέλεση και στατιστικά μοντέλου, Token και χρόνου.
* **Αξιόπιστες συνεδρίες** — μόνιμο ιστορικό, μακροχρόνια μνήμη, επαναφορά, επανεκτέλεση, ασφαλής διαγραφή και checkpoints.
* **Ενσωματωμένα εργαλεία ανάπτυξης** — αρχεία, editor, τερματικό, Git, λίστα εργασιών, Skills, Agents, MCP, OpenAPI, LSP και αυτοματισμοί.

Για εκτέλεση από τον πηγαίο κώδικα, ξεκινήστε το backend ξεχωριστά:

~~~bash
# Τερματικό 1: Desktop backend
soloncode serve 4808

# Τερματικό 2: Desktop client
cd soloncode-desktop
npm install
npm run tauri:dev
~~~

Η λειτουργία ανάπτυξης συνδέεται στη θύρα 4808 και δεν ξεκινά ούτε εντοπίζει αυτόματα τη διεργασία backend. Δείτε το [Desktop README](soloncode-desktop/README.md) και τον [οδηγό έναρξης SolonCode Desk](docs/soloncode-desk-getting-started.md).

## Τεκμηρίωση

Για περισσότερες λεπτομέρειες διαμόρφωσης, επισκεφθείτε την [Επίσημη Τεκμηρίωση](https://solon.noear.org/article/soloncode).

## Συνεισφορά

Αν ενδιαφέρεστε να συνεισφέρετε κώδικα, διαβάστε τα [Έγγραφα Συνεισφοράς](https://solon.noear.org/article/623) πριν υποβάλετε PR.

## Ανάπτυξη Βασισμένη στο SolonCode

Αν χρησιμοποιήσετε το "soloncode" στο όνομα του έργου σας (π.χ. "soloncode-dashboard" ή "soloncode-app"), παρακαλώ αναφέρετε στο README ότι το έργο δεν αναπτύσσεται επίσημα από την ομάδα OpenSolon και δεν έχει καμία σχέση.

## Συχνές ερωτήσεις: Ποια είναι η διαφορά από το Claude Code;

Είναι λειτουργικά παρόμοια, με βασικές διαφορές:

* Αναπτύχθηκε με Java, 100% ανοιχτού κώδικα. Συμβατό με BiSheng JDK (Huawei) και Harmony PC.
* Πλήρως καθοδηγούμενο και κατασκευασμένο με κινέζικες prompt
* Ανεξάρτητο από πάροχο. Διαμορφώστε τα μοντέλα ανάλογα με τις ανάγκες. Η επανάληψη μοντέλων θα μειώσει τα κενά και το κόστος, καθιστώντας την ευέλικτη διαμόρφωση σημαντική.
* Υποστηρίζει ταυτόχρονα τη διεπαφή γραμμής εντολών τερματικού (CLI), τη διεπαφή περιηγητή (WEB) και τη διεπαφή IDE επιφάνειας εργασίας (Desktop).
* Υποστηρίζει Web, πρωτόκολλο ACP για απομακρυσμένη επικοινωνία.
