#!/usr/bin/env python3
import argparse
import datetime
import io
import json
import math
import os
import random
import re
import shutil
import string
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
from functools import cache
from pathlib import Path
from xml.etree import ElementTree

import numpy
from PIL import Image, ImageChops, ImageDraw, ImageFilter

import tiles

PACKAGE = "dev.tn3w.shelf"
DATA = f"/data/data/{PACKAGE}/files"
ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android"
STRINGS = ANDROID / "app/src/main/res"
FASTLANE = ANDROID / "app/fastlane/metadata/android"
DARK = ANDROID / "design/screenshots/dark"
CACHE = Path(tempfile.gettempdir()) / "shelf-screenshots"
SERIAL = os.environ.get("SHELF_SERIAL", "emulator-5554")
AVD = os.environ.get("SHELF_AVD", "shelf-screenshots")
EMULATOR = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk")) / "emulator"
CAMERA = CACHE / "camera.mp4"
SCREENS = ("1_home", "2_listen", "3_reader", "4_book", "5_wrapped", "6_library",
           "7_explore", "8_scan")
BOTTOM_HEAVY = {"2_listen", "5_wrapped"}
FORMATS = "EPUB · PDF · FB2 · CBZ · TXT"
DEMO = ["am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command"]
DAY = 86_400_000
MINUTE = 60_000
DAILY_GOAL = 20
STREAK = 13
HISTORY = 24
CHAPTER = 2
STATUS_BAR = 137
SCENE_SIZE = (1440, 1920)
BOOK_HEIGHT = 700
BOOK_CENTER = 580
BAND_MARGIN = 60
SHEET_MARGIN = 130
SHEET_TITLE_TARGET = 1550
VIDEO_FRAMES = 40
PAGES = 24
SMALL_WORDS = {"a", "an", "and", "at", "for", "in", "of", "on", "the", "to", "with"}
NUMBERING = re.compile(r"^\s*(?:\w+\s+)?(?:[IVXLC]+|\d+)\s*[.:)]\s+", re.IGNORECASE)

SHELVES = dict(
    reading=[17091839, 16509148, 18203673, 24178205, 17370186],
    want=[46881, 2163649, 5819456, 46125, 49488, 7924103, 52114, 17370186],
    finished=[468431, 679358, 19096402, 103123, 471576, 63073, 85892],
)
SKIPPED = {82563, 82536, 82537, 82548, 82560, 82565, 82586, 244537, 17732}
ASIAN_SHELVES = dict(
    ja=dict(reading=[77746, 63073], want=[468431, 471576, 29983, 471565],
            finished=[166894, 262421, 1095427, 21177]),
    ko=dict(reading=[17091839, 5781992], want=[77746, 32466, 675783, 15992072],
            finished=[63073, 796465, 66554, 21177]),
    lv=dict(reading=[17091839, 24178205], want=[18766691, 18203673, 17332479, 7924103],
            finished=[19096402, 1230613, 103123, 274505]),
)


def wikisource(language, page):
    return f"https://ws-export.wmcloud.org/?lang={language}&format=epub-3&page={page}"


LOCALES = {
    "en": dict(chapter=6, folder="en-US", reader=(262421, 1661), copy=[
        ("Your books, offline", "No account · No ads"),
        ("Listen to audiobooks", "Your own files, with chapters"),
        ("Read your own files", FORMATS),
        ("Find any book", "Fast offline search"),
        ("Relive your reading year", "Stats, streaks and a yearly recap"),
        ("Switch from Goodreads", "Import shelves via CSV"),
        ("Discover your next read", "Picks made on your phone"),
        ("Scan any cover", "Recognized on your phone")]),
    "de": dict(chapter=2, folder="de", reader=(25397, 50965), copy=[
        ("Deine Bücher, offline", "Kein Konto · Keine Werbung"),
        ("Hör deine Hörbücher", "Eigene Dateien, mit Kapiteln"),
        ("Lies deine eigenen Dateien", FORMATS),
        ("Finde jedes Buch", "Schnelle Offline-Suche"),
        ("Dein Lesejahr im Rückblick", "Statistiken, Lesesträhnen, Jahresrückblick"),
        ("Wechsel von Goodreads", "Regale per CSV importieren"),
        ("Entdecke neue Bücher", "Tipps direkt auf dem Handy"),
        ("Scanne jedes Cover", "Erkannt auf dem Handy")]),
    "fr": dict(chapter=5, folder="fr", reader=(1064277, 32854), copy=[
        ("Vos livres, hors ligne", "Sans compte · Sans pub"),
        ("Écoutez vos livres audio", "Vos fichiers, avec chapitres"),
        ("Lisez vos fichiers", FORMATS),
        ("Trouvez vos livres", "Recherche rapide hors ligne"),
        ("Revivez votre année de lecture", "Stats, séries et bilan annuel"),
        ("Quittez Goodreads", "Import CSV de vos étagères"),
        ("Votre prochaine lecture", "Suggestions sur l’appareil"),
        ("Scannez une couverture", "Reconnue sur l’appareil")]),
    "es": dict(chapter=8, folder="es", reader=(503503, 44512), copy=[
        ("Tus libros, sin conexión", "Sin cuenta · Sin anuncios"),
        ("Escucha audiolibros", "Tus archivos, con capítulos"),
        ("Lee tus archivos", FORMATS),
        ("Encuentra cualquier libro", "Búsqueda rápida sin red"),
        ("Revive tu año lector", "Estadísticas, rachas y resumen anual"),
        ("Ven desde Goodreads", "Importa estantes en CSV"),
        ("Tu próxima lectura", "Sugerencias en tu móvil"),
        ("Escanea una portada", "Reconocida en tu móvil")]),
    "cs": dict(chapter=6, folder="cs", reader=(575625, 13083), copy=[
        ("Vaše knihy, offline", "Bez účtu · Bez reklam"),
        ("Poslouchejte audioknihy", "Vlastní soubory, s kapitolami"),
        ("Čtěte vlastní soubory", FORMATS),
        ("Najděte jakoukoli knihu", "Rychlé offline hledání"),
        ("Váš čtenářský rok", "Statistiky, série a roční souhrn"),
        ("Přejděte z Goodreads", "Import polic přes CSV"),
        ("Objevte další četbu", "Tipy přímo v telefonu"),
        ("Naskenujte jakoukoli obálku", "Rozpoznáno v telefonu")]),
    "et": dict(chapter=5, folder="et", reader=(24156, 43), scan=5819456, copy=[
        ("Sinu raamatud, offline", "Ilma konto ja reklaamita"),
        ("Kuula audioraamatuid", "Sinu failid, peatükkidega"),
        ("Loe oma faile", FORMATS),
        ("Leia iga raamat", "Kiire offline-otsing"),
        ("Sinu lugemisaasta", "Statistika, seeriad ja aastakokkuvõte"),
        ("Tule üle Goodreadsist", "Riiulite import CSV-ga"),
        ("Leia järgmine lugemine", "Soovitused sinu telefonis"),
        ("Skaneeri iga kaant", "Tuvastatakse telefonis")]),
    "fi": dict(chapter=6, folder="fi", reader=(262454, 32536), copy=[
        ("Kirjasi, offline", "Ei tiliä · Ei mainoksia"),
        ("Kuuntele äänikirjoja", "Omat tiedostot, lukuineen"),
        ("Lue omia tiedostoja", FORMATS),
        ("Löydä mikä tahansa kirja", "Nopea offline-haku"),
        ("Lukuvuotesi koosteena", "Tilastot, putket ja vuosikooste"),
        ("Vaihda Goodreadsista", "Tuo hyllyt CSV:llä"),
        ("Löydä seuraava luettava", "Suositukset puhelimessasi"),
        ("Skannaa mikä tahansa kansi", "Tunnistus puhelimessa")]),
    "ja": dict(chapter=1, folder="ja", scan=24034,
               reader=(1174972, wikisource("ja", "こゝろ")), titles={1174972: "こころ"}, copy=[
        ("あなたの本を、 オフラインで", "アカウントなし · 広告なし"),
        ("オーディオブックを 聴く", "自分のファイルを章ごとに"),
        ("自分のファイルを 読む", FORMATS),
        ("どんな本も すぐ見つかる", "高速オフライン検索"),
        ("あなたの 読書の一年", "統計・連続記録・年間まとめ"),
        ("Goodreads から乗り換え", "CSV で本棚をインポート"),
        ("次に読む本を 見つけよう", "スマホ上で作るおすすめ"),
        ("どんな表紙も スキャン", "スマホ上で認識")]),
    "ko": dict(chapter=6, folder="ko", reader=(468431, 64317), scan=102749, copy=[
        ("내 책, 오프라인으로", "계정 없음 · 광고 없음"),
        ("오디오북 듣기", "내 파일을 챕터별로"),
        ("내 파일 읽기", FORMATS),
        ("어떤 책이든 찾기", "빠른 오프라인 검색"),
        ("나의 독서 한 해", "통계 · 연속 기록 · 연말 결산"),
        ("Goodreads에서 옮기기", "CSV로 서재 가져오기"),
        ("다음 책 발견하기", "내 폰에서 만든 추천"),
        ("표지를 스캔하세요", "내 폰에서 인식")]),
    "lv": dict(chapter=6, folder="lv", reader=(85892, 345), copy=[
        ("Jūsu grāmatas, bezsaistē", "Bez konta · Bez reklāmām"),
        ("Klausieties audiogrāmatas", "Jūsu faili, ar nodaļām"),
        ("Lasiet savus failus", FORMATS),
        ("Atrodiet jebkuru grāmatu", "Ātra bezsaistes meklēšana"),
        ("Jūsu lasīšanas gads", "Statistika, sērijas un gada kopsavilkums"),
        ("Pārejiet no Goodreads", "Plauktu imports ar CSV"),
        ("Atklājiet nākamo grāmatu", "Ieteikumi jūsu tālrunī"),
        ("Skenējiet jebkuru vāku", "Atpazīts jūsu tālrunī")]),
    "pl": dict(chapter=6, folder="pl", reader=(262454, 34079), scan=49488, copy=[
        ("Twoje książki, offline", "Bez konta · Bez reklam"),
        ("Słuchaj audiobooków", "Własne pliki, z rozdziałami"),
        ("Czytaj własne pliki", FORMATS),
        ("Znajdź każdą książkę", "Szybkie wyszukiwanie offline"),
        ("Twój rok z książkami", "Statystyki, serie i podsumowanie roku"),
        ("Przejdź z Goodreads", "Import półek z CSV"),
        ("Odkryj następną lekturę", "Propozycje na Twoim telefonie"),
        ("Zeskanuj każdą okładkę", "Rozpoznawana na telefonie")]),
    "sv": dict(chapter=6, folder="sv",
               reader=(53919, wikisource("sv", "Tom_Sawyers_äventyr")), copy=[
        ("Dina böcker, offline", "Inget konto · Inga annonser"),
        ("Lyssna på ljudböcker", "Egna filer, med kapitel"),
        ("Läs dina egna filer", FORMATS),
        ("Hitta vilken bok som helst", "Snabb offlinesökning"),
        ("Ditt läsår i backspegeln", "Statistik, sviter och årssammanfattning"),
        ("Byt från Goodreads", "Importera hyllor via CSV"),
        ("Hitta nästa läsning", "Tips på din telefon"),
        ("Skanna vilket omslag som helst", "Känns igen på din telefon")]),
    "ru": dict(chapter=1, folder="ru-RU", catalogue="en",
               reader=(166952, wikisource("ru", "Белые_ночи_(Достоевский)")), titles={
        166952: "Белые ночи", 17091839: "Марсианин", 16509148: "Песнь Ахилла",
        46881: "Парк юрского периода", 2163649: "Автостопом по галактике",
        5819456: "Книжный вор", 46125: "Основание", 468431: "Великий Гэтсби",
        679358: "Коралина", 19096402: "Безмолвный пациент",
        103123: "451° по Фаренгейту"}, copy=[
        ("Ваши книги, офлайн", "Без аккаунта · Без рекламы"),
        ("Слушайте аудиокниги", "Свои файлы, с главами"),
        ("Читайте свои файлы", FORMATS),
        ("Найдите любую книгу", "Быстрый офлайн-поиск"),
        ("Ваш год в книгах", "Статистика, серии и итоги года"),
        ("Уходите из Goodreads", "Импорт полок через CSV"),
        ("Найдите что почитать", "Подборки на вашем телефоне"),
        ("Сканируйте любую обложку", "Распознаётся на телефоне")]),
    "pt-BR": dict(chapter=7, folder="pt-BR", catalogue="en",
                  reader=(1003017, 54829), titles={
        1003017: "Memórias Póstumas de Brás Cubas", 17091839: "Perdido em Marte",
        16509148: "A Canção de Aquiles", 2163649: "O Guia do Mochileiro das Galáxias",
        5819456: "A Menina que Roubava Livros", 46125: "Fundação",
        468431: "O Grande Gatsby", 19096402: "A Paciente Silenciosa"}, copy=[
        ("Seus livros, offline", "Sem conta · Sem anúncios"),
        ("Ouça audiolivros", "Seus arquivos, com capítulos"),
        ("Leia seus arquivos", FORMATS),
        ("Encontre qualquer livro", "Busca offline rápida"),
        ("Reviva seu ano de leitura", "Estatísticas, sequências e retrospectiva"),
        ("Saia do Goodreads", "Importe estantes via CSV"),
        ("Descubra sua próxima leitura", "Sugestões feitas no celular"),
        ("Escaneie qualquer capa", "Reconhecida no seu celular")]),
    "pt-PT": dict(chapter=8, folder="pt-PT", catalogue="en",
                  reader=(846513, 40409), titles={
        846513: "Os Maias", 17091839: "O Marciano", 16509148: "A Canção de Aquiles",
        46881: "Parque Jurássico", 2163649: "À Boleia pela Galáxia",
        5819456: "A Rapariga que Roubava Livros", 46125: "Fundação",
        468431: "O Grande Gatsby", 19096402: "A Paciente Silenciosa"}, copy=[
        ("Os seus livros, offline", "Sem conta · Sem anúncios"),
        ("Ouça audiolivros", "Os seus ficheiros, com capítulos"),
        ("Leia os seus ficheiros", FORMATS),
        ("Encontre qualquer livro", "Pesquisa rápida offline"),
        ("Reviva o seu ano de leitura", "Estatísticas, sequências e resumo anual"),
        ("Deixe o Goodreads", "Importe estantes via CSV"),
        ("Descubra a próxima leitura", "Sugestões feitas no telemóvel"),
        ("Digitalize qualquer capa", "Reconhecida no seu telemóvel")]),
    "it": dict(chapter=4, folder="it-IT", catalogue="en",
               reader=(1527356, 52484), titles={
        1527356: "Le avventure di Pinocchio", 17091839: "L’uomo di Marte",
        16509148: "La canzone di Achille",
        2163649: "Guida galattica per gli autostoppisti",
        5819456: "La bambina che salvava i libri", 46125: "Fondazione",
        468431: "Il grande Gatsby", 19096402: "La paziente silenziosa"}, copy=[
        ("I tuoi libri, offline", "Niente account · Niente pubblicità"),
        ("Ascolta audiolibri", "I tuoi file, con capitoli"),
        ("Leggi i tuoi file", FORMATS),
        ("Trova ogni libro", "Ricerca offline veloce"),
        ("Rivivi il tuo anno di lettura", "Statistiche, serie e riepilogo annuale"),
        ("Lascia Goodreads", "Importa gli scaffali da CSV"),
        ("Scopri la prossima lettura", "Consigli creati sul telefono"),
        ("Scansiona ogni copertina", "Riconosciuta sul telefono")]),
}


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


def download(url, path):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        address = urllib.parse.quote(url, safe=":/?&=%")
        headers = {"User-Agent": "shelf-screenshots"}
        request = urllib.request.Request(address, headers=headers)
        with urllib.request.urlopen(request, timeout=300) as response:
            path.write_bytes(response.read())
    return path


def catalogue_of(language):
    return LOCALES[language].get("catalogue", language)


def catalogue_packs(languages):
    gradle = (ANDROID / "app/build.gradle.kts").read_text()
    release = re.search(r'catalogueRelease = "([^"]+)"', gradle)[1]
    base = f"https://github.com/tn3w/Shelf/releases/download/{release}"
    folder = CACHE / release
    manifest = json.loads(download(f"{base}/manifest.json", folder / "manifest.json")
                          .read_text())
    wanted = {catalogue_of(language) for language in languages}
    for segment in manifest["segments"]:
        if segment["language"] in wanted and segment["pack"] in ("core", "ranks"):
            download(segment["url"], folder / f"{segment['id']}.bin")
    return folder


def epub(source):
    if isinstance(source, int):
        source = f"https://www.gutenberg.org/ebooks/{source}.epub3.images"
    name = re.sub(r"\W+", "-", source.split("//")[1])
    return download(source, CACHE / "books" / f"{name}.epub")


def silence():
    path = CACHE / "silence.m4a"
    if not path.exists():
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi",
                        "-i", "anullsrc=r=8000:cl=mono", "-t", "900",
                        "-c:a", "aac", "-b:a", "8k", path], check=True)
    return path


def wanted_works(language):
    locale = LOCALES[language]
    shelves = ASIAN_SHELVES.get(language, SHELVES)
    return [locale["reader"][0], locale.get("scan", 0), *sum(shelves.values(), [])]


def catalogue_books(languages, packs):
    requests = {language: dict(catalogue=catalogue_of(language),
                               works=wanted_works(language),
                               epub=str(epub(LOCALES[language]["reader"][1])))
                for language in languages}
    request, answer = CACHE / "books-request.json", CACHE / "books.json"
    request.write_text(json.dumps(requests))
    arguments = f"{packs} {request} {answer}"
    subprocess.run(["./gradlew", "-q", "screenshotBooks", f"--args={arguments}"],
                   cwd=ANDROID, check=True)
    return json.loads(answer.read_text())


def chapter_title(title):
    title = NUMBERING.sub("", title).strip() or title
    if not title.isupper() or re.fullmatch(r"[IVXLC]+\.?", title):
        return title
    words = string.capwords(title.lower()).split()
    return " ".join(word.lower() if index and word.lower() in SMALL_WORDS else word
                    for index, word in enumerate(words))


def is_native(title, language):
    return language not in ("ja", "ko") or any(char >= "\u3000" for char in title)


def shelves(language, found):
    locale = LOCALES[language]
    titles = locale.get("titles", {})
    books = {work: (work, titles.get(work, title.split(" ; ")[0]), author, cover)
             for work, title, author, cover in found["books"]}
    work, _, *own = locale["reader"]
    reader = books.get(work) or (work, *own, 0)
    used = {work}

    def take(works, count):
        chosen = [books[work] for work in works
                  if work in books and books[work][3] and work not in used][:count]
        used.update(book[0] for book in chosen)
        return chosen

    pools = ASIAN_SHELVES.get(language, SHELVES)
    popular = [tuple(book) for book in found["popular"]
               if book[0] not in SKIPPED and is_native(book[1], language)]
    books |= {book[0]: book for book in popular}
    reading, want = take(pools["reading"], 2), take(pools["want"], 4)
    finished = take(pools["finished"], 4)
    finished += take([book[0] for book in popular], HISTORY - len(finished))
    return dict(reader=reader, reading=reading, want=want, finished=finished,
                scan=books.get(locale.get("scan")) or want[0])


def finished_dates(count, today):
    start = datetime.date(today.year, 1, 4)
    span = (today - start).days - 6
    return [start + datetime.timedelta(days=span * (index + 1) // count)
            for index in range(count)]


def millis(day, hour=21):
    moment = datetime.datetime.combine(day, datetime.time(hour))
    return int(moment.timestamp() * 1000)


def entries(books, today):
    now = millis(today, 9)
    rated = random.Random(7)
    rows = [dict(work=book[0], shelf="Reading", title=book[1], author=book[2],
                 cover=book[3], updated=now - index * DAY, added=now - (index + 8) * DAY,
                 started=now - (index + 2) * 6 * DAY)
            for index, book in enumerate([books["reader"], *books["reading"]])]
    rows += [dict(work=book[0], shelf="Want", title=book[1], author=book[2],
                  cover=book[3], updated=now - (index + 3) * DAY,
                  added=now - (index + 3) * DAY)
             for index, book in enumerate(books["want"])]
    days = finished_dates(len(books["finished"]), today)
    rows += [dict(work=book[0], shelf="Read", title=book[1], author=book[2],
                  cover=book[3], rating=rated.choice([4, 5, 5, 5, 3, 4]),
                  updated=millis(day), finished=millis(day),
                  added=millis(day) - 30 * DAY,
                  started=millis(day) - rated.randint(4, 18) * DAY)
             for book, day in zip(reversed(books["finished"]), days)]
    return rows


def activity(today):
    pages = random.Random(today.year)
    start = datetime.date(today.year, 1, 1)
    count = (today - start).days + 1
    days = (start + datetime.timedelta(index) for index in range(count))
    choices = [0, 0, 12, 18, 24, 31, 38, 45, 52, 66]
    counts = {str(day): pages.choice(choices) for day in days}
    for index in range(STREAK):
        counts[str(today - datetime.timedelta(index))] = pages.randint(DAILY_GOAL + 2, 48)
    return {day: count for day, count in counts.items() if count}


def audiobook(reader, chapters, chapter):
    uri = f"file://{DATA}/audio/screenshots/silence.m4a"
    tracks = [dict(uri=uri, title=chapter_title(title),
                   millis=(14 + (index * 7 + len(title)) % 23) * MINUTE)
              for index, (title, _) in enumerate(chapters)]
    position = dict(tracks=tracks, track=chapter, millis=4 * MINUTE + 12_000)
    return {str(reader[0]): position}


def library_state(language, books, theme, today):
    now = millis(today, 9)
    settings = dict(onboarded=True, language=catalogue_of(language), theme=theme,
                    wallpaperColors=False, dailyGoal=DAILY_GOAL, checkUpdates=False,
                    catalogueUpdates=False, lastCatalogueCheck=now, lastAppCheck=now)
    return dict(entries=entries(books, today), settings=settings)


def reading_state(books, chapters, chapter, today):
    reader = books["reader"]
    section = chapters[chapter][1]
    pages = 240
    page = pages * chapter // max(1, len(chapters))
    progress = dict(file=f"{reader[0]}.epub", section=section, page=page, pages=pages)
    return dict(progress={str(reader[0]): progress}, activity=activity(today),
                audiobooks=audiobook(reader, chapters, chapter), listening=[reader[0]])


def adb(*arguments, timeout=120):
    command = ["adb", "-s", SERIAL, *map(str, arguments)]
    return subprocess.run(command, capture_output=True, timeout=timeout).stdout


def shell(*arguments):
    return adb("shell", *arguments).decode()


def push(source, target):
    adb("push", source, target)


def write_json(name, value):
    path = CACHE / name
    path.write_text(json.dumps(value, ensure_ascii=False))
    return path


def push_packs(language, packs):
    present = shell("ls", f"{DATA}/catalogue").split()
    for pack in packs.glob(f"{catalogue_of(language)}-*.bin"):
        if pack.name not in present:
            push(pack, f"{DATA}/catalogue/{pack.name}")


def seed(language, books, chapters, theme, packs):
    today = datetime.date.today()
    reader = books["reader"]
    shell("am", "force-stop", PACKAGE)
    owner = shell("stat", "-c", "%U", f"/data/data/{PACKAGE}").strip()
    shell("rm", "-rf", f"{DATA}/datastore", f"{DATA}/books", f"{DATA}/audio")
    shell("mkdir", "-p", f"{DATA}/datastore", f"{DATA}/books", f"{DATA}/catalogue",
          f"{DATA}/audio/screenshots")
    library = write_json("library.json", library_state(language, books, theme, today))
    chapter = LOCALES[language].get("chapter", CHAPTER)
    reading = write_json("reading.json", reading_state(books, chapters, chapter, today))
    push(library, f"{DATA}/datastore/library.json")
    push(reading, f"{DATA}/datastore/reading.json")
    push(epub(LOCALES[language]["reader"][1]), f"{DATA}/books/{reader[0]}.epub")
    push(silence(), f"{DATA}/audio/screenshots/silence.m4a")
    push_packs(language, packs)
    shell("chown", "-R", f"{owner}:{owner}", DATA)
    shell("restorecon", "-R", DATA)
    shell("cmd", "locale", "set-app-locales", PACKAGE, "--locales", language)
    shell("pm", "grant", PACKAGE, "android.permission.CAMERA")


def launch():
    shell("am", "force-stop", PACKAGE)
    shell("monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")


NODE = re.compile(r'text="([^"]*)"[^>]*content-desc="([^"]*)"[^>]*'
                  r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def nodes():
    xml = shell("uiautomator dump /sdcard/ui.xml > /dev/null && cat /sdcard/ui.xml")
    return NODE.findall(xml)


def wait(condition, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        found = condition(nodes())
        if found:
            return found
        time.sleep(0.3)
    raise TimeoutError(condition.__doc__)


def find(label, exact=True):
    def condition(current):
        return [node for node in current
                if (label in node[:2] if exact else label in node[0] + node[1])]
    condition.__doc__ = label
    return condition


def center(node):
    _, _, left, top, right, bottom = node
    return (int(left) + int(right)) // 2, (int(top) + int(bottom)) // 2


def tap(label, exact=True, timeout=30):
    shell("input", "tap", *center(wait(find(label, exact), timeout)[0]))


def open_until(label, target, attempts=3):
    for _ in range(attempts):
        tap(label)
        try:
            return wait(find(target), timeout=10)
        except TimeoutError:
            continue
    raise TimeoutError(target)


def back():
    shell("input", "keyevent", "BACK")


def screencap():
    return Image.open(io.BytesIO(adb("exec-out", "screencap", "-p"))).convert("RGB")


def changed(before, after):
    difference = ImageChops.difference(before, after).convert("L").point(
        lambda value: 255 if value > 24 else 0)
    return numpy.count_nonzero(numpy.asarray(difference)) / (after.width * after.height)


def settled(calm=3, timeout=30):
    time.sleep(1)
    previous, still = screencap(), 0
    deadline = time.monotonic() + timeout
    while still < calm and time.monotonic() < deadline:
        time.sleep(0.4)
        current = screencap()
        still = still + 1 if changed(previous, current) < 0.002 else 0
        previous = current
    return previous


def ink_height(image):
    gray = image.convert("L").crop((0, 300, image.width, image.height - 200))
    paper = gray.getpixel((0, 0))
    box = gray.point(lambda value: 255 if abs(value - paper) > 50 else 0).getbbox()
    return box[3] - box[1] if box else 0


def full_page():
    for _ in range(6):
        page = settled(calm=2)
        if ink_height(page) > 1500:
            return page
        shell("input", "tap", "1000", "1200")
    return page


@cache
def strings(language):
    folder = "values" if language == "en" else f"values-{android_folder(language)}"
    tree = ElementTree.parse(STRINGS / folder / "strings.xml")
    return {item.get("name"): item.text for item in tree.getroot().iter("string")}


def android_folder(language):
    code, _, region = language.partition("-")
    return f"{code}-r{region}" if region and region != "BR" else code


def label(language, name):
    return (strings(language).get(name) or strings("en")[name]).replace("\\'", "'")


def fragment(language, name):
    return max(re.split(r"%(?:\d\$)?[sd]", label(language, name)), key=len).strip()


def capture(language, books, folder):
    tabs = [label(language, name) for name in ("home", "library", "explore", "search")]
    reader = books["reader"]
    folder.mkdir(parents=True, exist_ok=True)

    def save(name, image):
        image.save(folder / f"{name}.png")
        log(f"{language}: {name}")

    launch()
    open_until(reader[1], label(language, "remove_audiobook"))
    save("2_listen", settled())
    back()
    save("3_reader", full_page())
    back()
    wait(find(tabs[0]))
    save("1_home", settled())

    open_until(tabs[1], books["want"][0][1])
    save("6_library", settled())

    open_until(books["reading"][0][1], books["reading"][0][2])
    save("4_book", settled())

    tap(tabs[2])
    tap(fragment(language, "because_you_read"), exact=False)
    time.sleep(1)
    save("7_explore", settled())

    tap(tabs[0])
    tap(label(language, "stats"))
    tap(fragment(language, "wrapped_banner"), exact=False)
    time.sleep(2)
    save("5_wrapped", settled(calm=2))
    launch()
    capture_scan(language, folder, tabs[3])


def capture_scan(language, folder, search):
    again = label(language, "scan_again")
    tap(search)
    tap(label(language, "scan"))
    started = time.monotonic()
    while not find(again)(nodes()):
        if time.monotonic() - started > 120:
            return log(f"{language}: cover not recognized")
        if time.monotonic() - started > 15:
            shell("input", "tap", "540", "800")
        time.sleep(2)
    screen = settled()
    lower = [int(node[3]) for node in nodes() if node[0] and int(node[3]) > 1200]
    save_scan = folder / "8_scan.png"
    screen.save(save_scan)
    (folder / "8_scan.txt").write_text(str(min(lower)))
    log(f"{language}: 8_scan")


def demo_mode():
    shell("settings", "put", "global", "sysui_demo_allowed", "1")
    shell(*DEMO, "exit")
    time.sleep(1)
    shell(*DEMO, "enter")
    shell(*DEMO, "clock", "-e", "hhmm", "0941")
    shell(*DEMO, "battery", "-e", "level", "100", "-e", "plugged", "false")
    shell(*DEMO, "network", "-e", "wifi", "show", "-e", "level", "4",
          "-e", "fully", "true")
    shell(*DEMO, "network", "-e", "mobile", "hide")
    shell(*DEMO, "notifications", "-e", "visible", "false")


def cover_image(cover):
    url = f"https://covers.openlibrary.org/b/id/{cover}-L.jpg"
    return Image.open(download(url, CACHE / "covers" / f"{cover}.jpg")).convert("RGB")


def wood():
    width, height = SCENE_SIZE
    noise = numpy.random.default_rng(7)
    streaks = Image.fromarray(noise.normal(128, 40, (height // 60, width)).clip(0, 255)
                              .astype("uint8")).resize(SCENE_SIZE, Image.BICUBIC)
    grain = numpy.asarray(streaks) / 255 - 0.5
    plank = numpy.arange(width) // 360
    tint = noise.uniform(-0.06, 0.06, plank.max() + 1)[plank]
    y, x = numpy.mgrid[0:height, 0:width] / height
    light = 1.12 - 0.3 * ((x - 0.37) ** 2 + (y - 0.3) ** 2)
    seam = numpy.where(numpy.arange(width) % 360 < 3, 0.55, 1.0)
    shade = (0.95 + 0.22 * grain + tint) * light * seam
    color = numpy.array([178, 128, 84]) * shade[..., None]
    return Image.fromarray(color.clip(0, 255).astype("uint8"))


def volume(cover):
    image = cover_image(cover)
    width = round(BOOK_HEIGHT * image.width / image.height)
    front = image.resize((width, BOOK_HEIGHT), Image.LANCZOS)
    sheet = Image.new("RGBA", (width + PAGES, BOOK_HEIGHT + PAGES))
    draw = ImageDraw.Draw(sheet)
    draw.rectangle((PAGES, PAGES, width + PAGES, BOOK_HEIGHT + PAGES), fill="#E9E1D0")
    for offset in range(4, PAGES, 4):
        draw.line((width + offset, offset, width + offset, BOOK_HEIGHT + offset),
                  fill="#CFC5B0")
        draw.line((offset, BOOK_HEIGHT + offset, width + offset, BOOK_HEIGHT + offset),
                  fill="#CFC5B0")
    sheet.paste(front, (0, 0))
    return sheet


def shadow_of(book):
    shadow = Image.new("RGBA", (book.width + 160, book.height + 160), (25, 12, 0, 0))
    mask = Image.new("L", shadow.size)
    mask.paste(book.getchannel("A").point(lambda alpha: alpha * 0.6), (80, 80))
    shadow.putalpha(mask)
    return shadow


def scene_frame(table, book, shadow, step):
    phase = 2 * math.pi * step / VIDEO_FRAMES
    angle = 4 + 2 * math.sin(phase)
    scale = 1 + 0.03 * math.sin(2 * phase)
    drift = (round(12 * math.cos(phase)), round(12 * math.sin(phase)))
    scene = table.copy()
    for layer, offset in ((shadow, (30, 40)), (book, (0, 0))):
        size = (round(layer.width * scale), round(layer.height * scale))
        turned = layer.resize(size, Image.LANCZOS)
        turned = turned.rotate(angle, Image.BICUBIC, expand=True)
        if layer is shadow:
            turned = turned.filter(ImageFilter.GaussianBlur(28))
        left = (SCENE_SIZE[0] - turned.width) // 2 + offset[0] + drift[0]
        top = BOOK_CENTER - turned.height // 2 + offset[1] + drift[1]
        scene.alpha_composite(turned, (left, top))
    width, height = SCENE_SIZE
    camera = Image.new("RGB", (width * 16 // 9, height * 16 // 9 // 2 * 2), "#9A6B45")
    camera.paste(scene.convert("RGB"), (0, (camera.height - height) // 2))
    return camera


def scene(cover):
    path = CACHE / "scenes" / f"{cover}.mp4"
    if path.exists():
        return path
    frames = CACHE / "frames"
    shutil.rmtree(frames, ignore_errors=True)
    frames.mkdir(parents=True)
    table, book = wood().convert("RGBA"), volume(cover)
    shadow = shadow_of(book)
    for step in range(VIDEO_FRAMES):
        frame = scene_frame(table, book, shadow, step)
        frame.save(frames / f"{step:03d}.jpg", quality=92)
    path.parent.mkdir(exist_ok=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", "10", "-i",
                    frames / "%03d.jpg", "-c:v", "libx264", "-pix_fmt", "yuv420p", path],
                   check=True)
    return path


def start_emulator():
    if adb("get-state", timeout=10).strip() != b"device":
        command = [EMULATOR / "emulator", "-avd", AVD, "-no-snapshot", "-no-audio",
                   "-camera-back", f"videofile:{CAMERA}"]
        subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
        adb("wait-for-device", timeout=300)
        while shell("getprop", "sys.boot_completed").strip() != "1":
            time.sleep(1)
        time.sleep(5)
    adb("root")
    adb("wait-for-device")
    for scale in ("window_animation_scale", "transition_animation_scale",
                  "animator_duration_scale"):
        shell("settings", "put", "global", scale, "0")
    demo_mode()


def raw_folder(language, theme):
    return CACHE / "raw" / f"{language}-{theme}"


def capture_all(plan, found, packs):
    shutil.copy(scene(shelves(plan[0][0], found[plan[0][0]])["scan"][3]), CAMERA)
    start_emulator()
    try:
        for language, themes in plan:
            books = shelves(language, found[language])
            shutil.copy(scene(books["scan"][3]), CAMERA)
            chapters = found[language]["chapters"]
            for theme in themes:
                log(f"{language}: {theme}")
                seed(language, books, chapters, theme, packs)
                demo_mode()
                capture(language, books, raw_folder(language, theme))
    finally:
        shell("am", "force-stop", PACKAGE)
        shell(*DEMO, "exit")


def output_folder(language, theme):
    if theme == "Dark":
        return DARK
    return FASTLANE / LOCALES[language]["folder"] / "images/phoneScreenshots"


def without_band(screen, title_top):
    scale = screen.height / SCENE_SIZE[1]
    bottom_of_book = BOOK_CENTER + (BOOK_HEIGHT + PAGES) // 2
    start = round((bottom_of_book + BAND_MARGIN) * scale)
    empty = title_top - SHEET_MARGIN - start
    band = max(0, min(title_top - SHEET_TITLE_TARGET, empty))
    joined = Image.new(screen.mode, (screen.width, screen.height - band))
    joined.paste(screen.crop((0, 0, screen.width, start)))
    joined.paste(screen.crop((0, start + band, screen.width, screen.height)), (0, start))
    return joined


def compose_all(plan):
    for language, themes in plan:
        copy = LOCALES[language]["copy"]
        size = tiles.headline_size([headline for headline, _ in copy])
        for theme in themes:
            source, target = raw_folder(language, theme), output_folder(language, theme)
            target.mkdir(parents=True, exist_ok=True)
            for index, (name, (headline, detail)) in enumerate(zip(SCREENS, copy)):
                if not (source / f"{name}.png").exists():
                    continue
                screen = Image.open(source / f"{name}.png")
                if name == "8_scan":
                    title_top = int((source / "8_scan.txt").read_text())
                    screen = without_band(screen, title_top)
                dark = theme == "Dark"
                flipped = name in BOTTOM_HEAVY
                tile = tiles.compose(screen, index, headline, detail, size, dark, flipped)
                tile.save(target / f"{name}.jpg", quality=90, optimize=True)
            log(f"{language}: {theme} → {target.relative_to(ROOT)}")


def variants(languages, dark_only):
    if dark_only:
        return [("en", ["Dark"])]
    return [(language, ["Light", "Dark"] if language == "en" else ["Light"])
            for language in languages]


def main():
    parser = argparse.ArgumentParser(description="Capture and compose store screenshots")
    parser.add_argument("--locales", nargs="+", choices=LOCALES, default=list(LOCALES))
    parser.add_argument("--dark", action="store_true", help="only the dark English set")
    parser.add_argument("--compose-only", action="store_true",
                        help="reuse cached captures without the emulator")
    arguments = parser.parse_args()
    plan = variants(arguments.locales, arguments.dark)
    if not arguments.compose_only:
        languages = [language for language, _ in plan]
        packs = catalogue_packs(languages)
        capture_all(plan, catalogue_books(languages, packs), packs)
    compose_all(plan)


if __name__ == "__main__":
    main()
