#!/usr/bin/env python3
"""Download every file of a BnF transfer share folder (transfert.bnf.fr GoDrive)."""

import argparse
import html
import http.cookiejar
import os
import re
import shutil
import urllib.parse
import urllib.request

BASE = "https://transfert.bnf.fr"
ACTION = BASE + "/webclient/godrive/PublicGoDrive.xhtml"
AJAX = {"Faces-Request": "partial/ajax", "X-Requested-With": "XMLHttpRequest"}
AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Firefox/140.0"
TIMEOUT = 300
ROW = re.compile(r'data-ri="(\d+)" data-rk="([^"]*)".*?class="NameColumn">(.*?)</td>', re.S)
VIEW_STATE = re.compile(r'ViewState[^>]*value="([^"]+)"|ViewState[^>]*><!\[CDATA\[([^\]]+)')
BUTTON = re.compile(r'<form id="([^"]+)".*?<button id="([^"]+)"', re.S)


class Share:
    def __init__(self, link, path):
        cookies = urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        self.opener = urllib.request.build_opener(cookies)
        self.opener.addheaders = [("User-Agent", AGENT)]
        self.link, self.path, self.view = link, path, None

    def remember(self, page):
        match = VIEW_STATE.search(page)
        if match:
            self.view = html.unescape(match.group(1) or match.group(2))
        return page

    def post(self, data, headers=AJAX):
        body = urllib.parse.urlencode(data | {"javax.faces.ViewState": self.view})
        request = urllib.request.Request(ACTION, body.encode(), headers)
        return self.opener.open(request, timeout=TIMEOUT)

    def ajax(self, data):
        return self.remember(self.post(data).read().decode())

    def folder(self):
        url = f"{BASE}/link/{self.link}"
        page = self.remember(self.opener.open(url, timeout=TIMEOUT).read().decode())
        for name in self.path:
            row = next(row for row, _, entry in entries(page) if entry == name)
            source = f"fileTable:{row}:j_id_2d"
            page = self.ajax({
                "javax.faces.partial.ajax": "true",
                "javax.faces.source": source,
                "javax.faces.partial.execute": source,
                "javax.faces.partial.render": "breadcrumbForm fileList toolbarForm messages",
                source: source,
                "fileList_SUBMIT": "1",
            })
        return page

    def download(self, key, target):
        buttons = self.ajax({
            "javax.faces.partial.ajax": "true",
            "javax.faces.source": "fileTable",
            "javax.faces.partial.execute": "fileTable",
            "javax.faces.partial.render": "multiRowButtons",
            "javax.faces.behavior.event": "rowSelect",
            "javax.faces.partial.event": "rowSelect",
            "fileTable_instantSelection": key,
            "fileTable_selection": key,
            "fileList_SUBMIT": "1",
        })
        form, button = BUTTON.search(buttons).groups()
        response = self.post({form + "_SUBMIT": "1", button: ""}, {})
        with open(target + ".part", "wb") as output:
            shutil.copyfileobj(response, output, 1 << 20)
        os.replace(target + ".part", target)


def entries(page):
    rows = ROW.findall(page)
    return [(int(row), key, re.sub(r"<[^>]+>", "", name).strip()) for row, key, name in rows]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("link", help="share id from https://transfert.bnf.fr/link/<id>")
    parser.add_argument("directory", help="where the zip files go")
    parser.add_argument("path", nargs="*", help="folder names inside the share")
    chosen = parser.parse_args()
    share = Share(chosen.link, chosen.path)
    os.makedirs(chosen.directory, exist_ok=True)
    for _, _, name in entries(share.folder()):
        target = os.path.join(chosen.directory, name + ".zip")
        if os.path.exists(target):
            continue
        key = next(key for _, key, entry in entries(share.folder()) if entry == name)
        share.download(key, target)
        print("got", target, flush=True)


if __name__ == "__main__":
    main()
