"""產生 app/src/main/assets/world_cities.json（世界主要城市，供離線搜尋）。

需要：pip install geonamescache babel opencc-python-reimplemented
格式：[[英文名, 繁中名(可空), 國家(繁中), 緯度, 經度, 人口, 熱門(1/0)], ...]，依人口排序。
「熱門」是台灣旅客常去的城市，搜尋時排在前面。
台灣城市不在此清單（由 taiwan_places.json 處理）。
"""
import json
import re
from collections import Counter
from pathlib import Path

import geonamescache
from babel import Locale
from opencc import OpenCC

MIN_POPULATION = 300_000
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/world_cities.json"

s2twp = OpenCC("s2twp")
cjk = re.compile(r"[一-鿿]")
kana_hangul = re.compile(r"[぀-ヿ가-힯]")
territories = dict(Locale.parse("zh_Hant_TW").territories)
territories.update({"HK": "香港", "MO": "澳門", "AE": "阿聯", "CD": "剛果民主共和國", "KR": "韓國"})

# 台灣慣用譯名（資料中的寫法不一致或是日文漢字時覆蓋）
OVERRIDES = {
    "Sydney": "雪梨",
    "Yokohama": "橫濱",
    "Perth": "伯斯",
    "Auckland": "奧克蘭",
    "Washington": "華盛頓",
    "New York City": "紐約",
}


POPULAR = {
    "Tokyo", "Osaka", "Kyoto", "Sapporo", "Fukuoka", "Nagoya", "Naha", "Yokohama", "Kobe", "Sendai", "Hiroshima",
    "Seoul", "Busan", "Incheon", "Hong Kong", "Macau", "Shanghai", "Beijing", "Shenzhen", "Guangzhou", "Xiamen",
    "Bangkok", "Chiang Mai", "Singapore", "Kuala Lumpur", "Ho Chi Minh City", "Hanoi", "Da Nang", "Manila",
    "Cebu City", "Jakarta", "Denpasar", "Phnom Penh", "Sydney", "Melbourne", "Brisbane", "Perth", "Auckland",
    "New York City", "Los Angeles", "San Francisco", "Seattle", "Chicago", "Boston", "Las Vegas", "Honolulu",
    "Vancouver", "Toronto", "London", "Paris", "Rome", "Milan", "Barcelona", "Madrid", "Amsterdam", "Berlin",
    "Munich", "Frankfurt am Main", "Vienna", "Prague", "Zurich", "Istanbul", "Dubai",
}


def zh_name(city):
    """把各種中文別名轉成台灣用字後，取出現最多次的寫法（同票取第一個）。"""
    if city["name"] in OVERRIDES:
        return OVERRIDES[city["name"]]
    candidates = [a for a in city["alternatenames"] if cjk.search(a) and not kana_hangul.search(a)]
    if not candidates:
        return ""
    converted = [s2twp.convert(a) for a in candidates]
    counts = Counter(converted)
    best = max(counts.values())
    name = next(c for c in converted if counts[c] == best)
    # 「羅馬市」若也有「羅馬」這個寫法，就用較短的
    if name.endswith("市") and len(name) > 2 and name[:-1] in converted:
        name = name[:-1]
    return name.removesuffix("都會區")


def main():
    cities = [c for c in geonamescache.GeonamesCache().get_cities().values() if c["countrycode"] != "TW"]
    # 熱門城市只認同名中人口最多的那一個（避免把加拿大的 London 當成倫敦）
    popular_ids = set()
    for name in POPULAR:
        same = [c for c in cities if c["name"] == name]
        if same:
            popular_ids.add(max(same, key=lambda c: c["population"])["geonameid"])
    rows = []
    for c in cities:
        popular = c["geonameid"] in popular_ids
        if c["population"] < MIN_POPULATION and not popular:
            continue
        rows.append([
            c["name"],
            zh_name(c),
            territories.get(c["countrycode"], c["countrycode"]),
            round(c["latitude"], 4),
            round(c["longitude"], 4),
            c["population"],
            1 if popular else 0,
        ])
    rows.sort(key=lambda r: -r[5])
    OUT.write_text(json.dumps(rows, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"{len(rows)} cities -> {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
