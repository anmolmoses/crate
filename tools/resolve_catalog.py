"""Builds app/src/main/assets/catalog.json from the iTunes Search API.

For each album it records the year, genre, one track with a 30-second preview,
and the 600px cover URL. The app downloads covers and previews at runtime.

Usage: python tools/resolve_catalog.py
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ALBUMS = [
    ("Random Access Memories", "Daft Punk"),
    ("The Dark Side of the Moon", "Pink Floyd"),
    ("Abbey Road", "The Beatles"),
    ("Thriller", "Michael Jackson"),
    ("Rumours", "Fleetwood Mac"),
    ("Nevermind", "Nirvana"),
    ("OK Computer", "Radiohead"),
    ("To Pimp a Butterfly", "Kendrick Lamar"),
    ("Blonde", "Frank Ocean"),
    ("After Hours", "The Weeknd"),
    ("Currents", "Tame Impala"),
    ("21", "Adele"),
    ("Back in Black", "AC/DC"),
    ("Kind of Blue", "Miles Davis"),
    ("Purple Rain", "Prince"),
    ("The Miseducation of Lauryn Hill", "Lauryn Hill"),
    ("Discovery", "Daft Punk"),
    ("Born to Die", "Lana Del Rey"),
    ("AM", "Arctic Monkeys"),
    ("Hotel California", "Eagles"),
    ("Graduation", "Kanye West"),
    ("Melodrama", "Lorde"),
    ("Future Nostalgia", "Dua Lipa"),
    ("Led Zeppelin IV", "Led Zeppelin"),
    ("Appetite for Destruction", "Guns N' Roses"),
    ("Is This It", "The Strokes"),
    ("In Rainbows", "Radiohead"),
    ("channel ORANGE", "Frank Ocean"),
    ("SOS", "SZA"),
    ("Starboy", "The Weeknd"),
    ("Hybrid Theory", "Linkin Park"),
    ("A Night at the Opera", "Queen"),
    ("Back to Black", "Amy Winehouse"),
    ("Parachutes", "Coldplay"),
]

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "catalog.json")


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def search(title, artist):
    q = urllib.parse.urlencode({"term": f"{artist} {title}", "entity": "song", "limit": 25, "country": "US"})
    for _ in range(4):
        try:
            return json.load(urllib.request.urlopen("https://itunes.apple.com/search?" + q, timeout=20))["results"]
        except Exception as e:  # The API rate-limits at roughly 20 calls a minute.
            print("  retrying:", e, file=sys.stderr)
            time.sleep(8)
    return []


def main():
    result = []
    for title, artist in ALBUMS:
        rows = [r for r in search(title, artist) if r.get("previewUrl")]
        match = [r for r in rows
                 if norm(title) in norm(r.get("collectionName", ""))
                 and norm(artist)[:5] in norm(r.get("artistName", ""))]
        r = (match or rows or [None])[0]
        if r is None:
            print("not found:", title, file=sys.stderr)
            continue
        result.append(dict(
            title=title, artist=artist, year=int(r["releaseDate"][:4]),
            genre=r.get("primaryGenreName", ""), track=r["trackName"], preview=r["previewUrl"],
            artUrl=r["artworkUrl100"].replace("100x100bb", "600x600bb"),
        ))
        print(f"{title} -> {r.get('collectionName')} | {r['trackName']}", file=sys.stderr)
        time.sleep(3.2)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()
