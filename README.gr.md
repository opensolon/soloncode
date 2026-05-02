<div align="center">
<h1>SolonCode</h1>
<p>Ένας ανοιχτού κώδικα πράκτορας κωδικοποίησης βασισμένος στο <a href="https://github.com/opensolon/solon-ai">Solon AI</a> και Java (υποστηρίζει περιβάλλοντα εκτέλεσης Java8 έως Java26)</p>
<p>Τελευταία Έκδοση: v2026.5.2</p>
<img height="260" src="SHOW.png" />
<img height="260" src="SHOW2.png" />
</div>

<div align="center">

[中文](README.zh.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Deutsch](README.de.md) | [Français](README.fr.md) | [Español](README.es.md) | [Italiano](README.it.md)

[Русский](README.ru.md) | [العربية](README.ar.md) | [Português (BR)](README.br.md) | [ไทย](README.th.md) | [Tiếng Việt](README.vi.md) | [Polski](README.pl.md)

[বাংলা](README.bn.md) | [Bosanski](README.bs.md) | [Dansk](README.da.md) | [Ελληνικά](README.gr.md) | [Norsk](README.no.md) | [Türkçe](README.tr.md) | [Українська](README.uk.md)

</div>

## Εγκατάσταση και διαμόρφωση

Εγκατάσταση:

```bash
# Mac / Linux:
curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash

# Windows (PowerShell):
irm https://solon.noear.org/soloncode/setup.ps1 | iex
```

Διαμόρφωση (πρέπει να τροποποιηθεί μετά την εγκατάσταση):

* Κατάλογος εγκατάστασης: `~/soloncode/bin/`
* Εντοπίστε το αρχείο διαμόρφωσης `~/soloncode/config.yml` και τροποποιήστε τη διαμόρφωση `models` (κυρίως)
* Για τις επιλογές διαμόρφωσης του `models`, ανατρέξτε στο: [Διαμόρφωση Μοντέλου και Επιλογές Αιτημάτων](https://solon.noear.org/article/1087)

## Εκτέλεση

Εκτελέστε την εντολή `soloncode` από οποιονδήποτε κατάλογο στην κονσόλα (δηλαδή, τον χώρο εργασίας σας).

```bash
demo@MacBook-Pro ~ % soloncode
SolonCode v2026.5.2
/Users/noear
Tips: (esc) interrupt | '/exit' | '/resume' | '/clear' | '/model'

User
> 
```

Δοκιμή Λειτουργιών (δοκιμάστε τις παρακάτω εργασίες, από απλές σε σύνθετες):

* `你好`
* `用网络分析下 ai mcp 协议，然后生成个 ppt` // Προτείνεται η προηγούμενη εγκατάσταση κάποιων δεξιοτήτων
* `帮我设计一个 agent team（设计案存为 demo-dis.md），开发一个 solon + java17 的经典权限管理系统（demo-web），前端用 vue3，界面要简洁好看`


## Τεκμηρίωση

Για περισσότερες λεπτομέρειες διαμόρφωσης, επισκεφθείτε την [Επίσημη Τεκμηρίωση](https://solon.noear.org/article/soloncode).

## Συνεισφορά

Αν ενδιαφέρεστε να συνεισφέρετε κώδικα, διαβάστε τα [Έγγραφα Συνεισφοράς](https://solon.noear.org/article/623) πριν υποβάλετε PR.

## Ανάπτυξη Βασισμένη στο SolonCode

Αν χρησιμοποιήσετε το "soloncode" στο όνομα του έργου σας (π.χ. "soloncode-dashboard" ή "soloncode-app"), παρακαλώ αναφέρετε στο README ότι το έργο δεν αναπτύσσεται επίσημα από την ομάδα OpenSolon και δεν έχει καμία σχέση.

## Συχνές ερωτήσεις: Ποια είναι η διαφορά από το Claude Code και το OpenCode;

Είναι λειτουργικά παρόμοια, με βασικές διαφορές:

* Αναπτύχθηκε με Java, 100% ανοιχτού κώδικα.
* Ανεξάρτητο από πάροχο. Απαιτεί διαμόρφωση μοντέλου. Η επανάληψη μοντέλων θα μειώσει τα κενά και το κόστος, καθιστώντας την προσέγγιση ανεξάρτητη από πάροχο σημαντική.
* Εστιασμένο στη διεπαφή γραμμής εντολών τερματικού (CLI), εκτελείται μέσω εντολών συστήματος.
* Υποστηρίζει Web, πρωτόκολλο ACP για απομακρυσμένη επικοινωνία.