const FORMAT = 2
const SEPARATOR = "\u001f"
const YEAR_EPOCH = 1400
const WINDOW = 32768
const decoder = new TextDecoder()

class Reader {
  constructor(bytes, offset = 0) {
    this.bytes = bytes
    this.offset = offset
  }

  get hasMore() {
    return this.offset < this.bytes.length
  }

  byte() {
    return this.bytes[this.offset++]
  }

  varint() {
    let value = 0
    let shift = 0
    while (true) {
      const byte = this.byte()
      value += (byte & 0x7f) * 2 ** shift
      if (!(byte & 0x80)) return value
      shift += 7
    }
  }

  take(length) {
    this.offset += length
    return this.bytes.subarray(this.offset - length, this.offset)
  }

  text() {
    return decoder.decode(this.take(this.varint()))
  }

  rest() {
    return this.take(this.bytes.length - this.offset)
  }
}

export function postings(bytes) {
  const reader = new Reader(bytes)
  const values = []
  let current = 0
  while (reader.hasMore) values.push((current += reader.varint()))
  return values
}

function storedBlock(dictionary) {
  const length = dictionary.length
  const header = [0, length & 0xff, length >> 8, ~length & 0xff, (~length >> 8) & 0xff]
  return [new Uint8Array(header), dictionary]
}

export async function inflate(compressed, dictionary = new Uint8Array()) {
  const window = dictionary.subarray(-WINDOW)
  const parts = window.length ? [...storedBlock(window), compressed] : [compressed]
  const stream = new Blob(parts)
    .stream()
    .pipeThrough(new DecompressionStream("deflate-raw"))
  const output = new Uint8Array(await new Response(stream).arrayBuffer())
  return output.subarray(window.length)
}

function lengthPrefixed(raw) {
  const reader = new Reader(raw)
  const records = []
  while (reader.hasMore) records.push(reader.take(reader.varint()))
  return records
}

function lastAtMost(sorted, key) {
  let low = 0
  let high = sorted.length - 1
  while (low <= high) {
    const middle = (low + high) >> 1
    if (sorted[middle] <= key) low = middle + 1
    else high = middle - 1
  }
  return high
}

function contains(sorted, value) {
  const index = lastAtMost(sorted, value)
  return index >= 0 && sorted[index] === value
}

function cached(load) {
  const entries = new Map()
  return key => {
    if (!entries.has(key)) entries.set(key, load(key))
    return entries.get(key)
  }
}

function integers(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  return Int32Array.from({ length: bytes.length / 4 }, (_, index) =>
    view.getInt32(index * 4, true),
  )
}

function table(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const count = view.getInt32(0, true)
  const data = 4 + 4 * (count + 1)
  const at = index => {
    const start = view.getInt32(4 + 4 * index, true)
    const end = view.getInt32(8 + 4 * index, true)
    return bytes.subarray(data + start, data + end)
  }
  return { count, at }
}

function blockIndex(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const count = view.getInt32(0, true)
  const firsts = Array.from({ length: count }, (_, index) =>
    view.getInt32(4 + 4 * index, true),
  )
  return { firsts, blocks: table(bytes.subarray(4 + 4 * count)) }
}

function sections(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  if (decoder.decode(bytes.subarray(0, 4)) !== "SHLF") throw Error("not a shelf segment")
  if (view.getInt32(4, true) !== FORMAT) throw Error("unsupported segment version")
  const result = {}
  for (let index = 0; index < view.getInt32(8, true); index++) {
    const entry = 12 + index * 24
    const name = decoder.decode(bytes.subarray(entry, entry + 16)).replace(/\0+$/, "")
    const offset = view.getInt32(entry + 16, true)
    result[name] = bytes.subarray(offset, offset + view.getInt32(entry + 20, true))
  }
  const meta = Object.fromEntries(
    decoder
      .decode(result.meta)
      .split("\n")
      .filter(line => line.includes("="))
      .map(line => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)]),
  )
  return { ...result, meta }
}

function termBlocks(bytes, withPostings) {
  const { count, at } = table(bytes)
  const firstTerms = Array.from({ length: count }, (_, index) =>
    new Reader(at(index), 1).text(),
  )

  const readTerm = (reader, text) => {
    const titleCount = reader.varint()
    const authorCount = reader.varint()
    const term = {
      text,
      titleCount,
      authorCount,
      frequency: titleCount + authorCount,
    }
    if (!withPostings) return term
    const titleLength = reader.varint()
    const authorLength = reader.varint()
    const titles = reader.take(titleLength)
    const authors = reader.take(authorLength)
    return {
      ...term,
      titleWorks: () => postings(titles),
      authorSlots: () => postings(authors),
    }
  }

  const block = cached(index => {
    const reader = new Reader(at(index))
    const terms = []
    let previous = ""
    while (reader.hasMore) {
      const text = previous.slice(0, reader.varint()) + reader.text()
      terms.push(readTerm(reader, text))
      previous = text
    }
    return terms
  })

  const find = text => {
    const index = lastAtMost(firstTerms, text)
    return index < 0 ? undefined : block(index).find(term => term.text === text)
  }

  const scan = prefix => {
    const terms = []
    const start = Math.max(0, lastAtMost(firstTerms, prefix))
    for (let index = start; index < count; index++) {
      const first = firstTerms[index]
      if (first > prefix && !first.startsWith(prefix)) break
      terms.push(
        ...block(index).filter(
          term => term.text.startsWith(prefix) && term.text !== prefix,
        ),
      )
    }
    return terms
  }

  return { block, find, scan }
}

function keyedTable(bytes) {
  const entries = table(bytes)
  const keys = Array.from({ length: entries.count }, (_, index) =>
    new Reader(entries.at(index)).text(),
  )
  return key => {
    const index = lastAtMost(keys, key)
    if (index < 0 || keys[index] !== key) return undefined
    const reader = new Reader(entries.at(index))
    reader.text()
    return reader
  }
}

export class Segment {
  constructor(bytes) {
    const section = sections(bytes)
    this.pack = section.meta.pack
    this.month = section.meta.month
    this.base = section.meta.base ?? this.month
    this.works = integers(section.works)
    this.tombstones = integers(section.tombstones)
    this.recordsPerBlock = Number(section.meta.records_per_block)
    this.termsPerBlock = Number(section.meta.terms_per_block)
    this.factTable = table(section.facts)
    this.headTable = table(section.heads)
    this.authorTable = table(section.authors)
    this.seriesTable = table(section.series)
    this.terms = termBlocks(section.terms, true)
    this.completionTable = keyedTable(section.completions)
    this.gramTable = keyedTable(section.grams)
    this.descriptions = blockIndex(section.descriptions)
    this.isbns = section.isbns && blockIndex(section.isbns)
    const tagTable = table(section.tags)
    this.tags = Array.from({ length: tagTable.count }, (_, id) => {
      const reader = new Reader(tagTable.at(id))
      const tag = {
        id,
        slug: reader.text(),
        label: reader.text(),
        category: reader.text(),
      }
      const count = reader.varint()
      return { ...tag, count, works: () => postings(reader.rest()) }
    })
    this.blocks = cached(key => {
      const [name, block] = key.split(":")
      const dictionary = name === "head" ? section.head_dictionary : undefined
      return inflate(this[`${name}Table`].at(Number(block)), dictionary).then(
        lengthPrefixed,
      )
    })
    this.descriptionBlock = cached(index => this.readDescriptions(index, section))
    this.isbnBlock = cached(index => this.readIsbns(index))
  }

  get isBase() {
    return this.base === this.month
  }

  localOf(work) {
    const index = lastAtMost(this.works, work)
    return index >= 0 && this.works[index] === work ? index : -1
  }

  async record(name, local) {
    const block = await this.blocks(`${name}:${Math.floor(local / this.recordsPerBlock)}`)
    return block[local % this.recordsPerBlock]
  }

  async facts(local) {
    const reader = new Reader(await this.record("fact", local))
    const authors = Array.from({ length: reader.varint() }, () => reader.varint())
    const year = reader.varint()
    const cover = reader.varint()
    const tags = Array.from({ length: reader.varint() }, () => reader.byte())
    const series = reader.varint() - 1
    return { authors, year: year && year + YEAR_EPOCH, cover, tags, series }
  }

  async heads(local) {
    const [title, subtitle = "", alternate = ""] = decoder
      .decode(await this.record("head", local))
      .split(SEPARATOR)
    return { title, subtitle, alternate }
  }

  async author(slot) {
    const reader = new Reader(await this.record("author", slot))
    const number = reader.varint()
    const born = reader.varint()
    return { number, born, name: reader.text(), works: postings(reader.rest()) }
  }

  series(slot) {
    const reader = new Reader(this.seriesTable.at(slot))
    const name = reader.text()
    return {
      name,
      members: Array.from({ length: reader.varint() }, () => reader.varint()),
    }
  }

  async readDescriptions(index, section) {
    const reader = new Reader(
      await inflate(this.descriptions.blocks.at(index), section.text_dictionary),
    )
    const first = this.descriptions.firsts[index]
    const result = new Map()
    while (reader.hasMore) {
      const marked = reader.varint()
      result.set(first + (marked >> 1), {
        text: reader.text(),
        translated: marked % 2 === 1,
      })
    }
    return result
  }

  async description(local) {
    const block = lastAtMost(this.descriptions.firsts, local)
    if (block < 0) return { text: "", translated: false }
    const found = (await this.descriptionBlock(block)).get(local)
    return found ?? { text: "", translated: false }
  }

  async readIsbns(index) {
    const reader = new Reader(await inflate(this.isbns.blocks.at(index)))
    let isbn = this.isbns.firsts[index]
    const result = new Map()
    while (reader.hasMore) result.set((isbn += reader.varint()), reader.varint())
    return result
  }

  async localOfIsbn(key) {
    if (!this.isbns) return -1
    const block = lastAtMost(this.isbns.firsts, key)
    if (block < 0) return -1
    return (await this.isbnBlock(block)).get(key) ?? -1
  }

  term(text) {
    return this.terms.find(text)
  }

  termById(id) {
    return this.terms.block(Math.floor(id / this.termsPerBlock))[id % this.termsPerBlock]
  }

  completions(prefix, limit) {
    const reader =
      prefix.length >= 2 && prefix.length <= 4 && this.completionTable(prefix)
    if (!reader) {
      return this.terms
        .scan(prefix)
        .sort((a, b) => b.frequency - a.frequency)
        .slice(0, limit)
    }
    const count = Math.min(reader.varint(), limit)
    return Array.from({ length: count }, () => this.termById(reader.varint()))
  }

  gramTerms(gram) {
    const reader = this.gramTable(gram)
    return reader ? postings(reader.rest()) : []
  }
}

export class Ranks {
  constructor(bytes) {
    const section = sections(bytes)
    this.works = Number(section.meta.works)
    this.terms = termBlocks(section.terms, false)
    this.index = blockIndex(section.popularity)
    this.block = cached(index => this.decode(index))
  }

  frequency(text) {
    return this.terms.find(text)?.frequency ?? 0
  }

  async decode(index) {
    const reader = new Reader(await inflate(this.index.blocks.at(index)))
    const rows = new Map()
    let work = this.index.firsts[index]
    while (reader.hasMore) {
      work += reader.varint()
      const score = reader.varint() / 10
      const readers = reader.varint()
      const ratings = reader.varint()
      const rating = reader.byte() / 20
      rows.set(work, { score, readers, ratings, rating, editions: reader.byte() })
    }
    return rows
  }

  async popularity(work) {
    const index = lastAtMost(this.index.firsts, work)
    return index < 0 ? undefined : (await this.block(index)).get(work)
  }
}

const COMPANION = new RegExp(
  "box(ed)? set|collection set|books? collection|\\d ?books? set|gift set|" +
    "books? bundle|\\buntitled\\b|\\bseries ?(box|set|collection)?\\s*$|" +
    "\\(series\\)|\\b\\d{1,2}\\s*[-–]\\s*\\d{1,2}\\b|omnibus|slipcase|" +
    "complete (series|collection|novels|saga|works)|collected (works|novels)|" +
    "trilogy|tetralogy|trilogie|gesamtausgabe|gesamtwerk|sammelband|" +
    "coffret|int[ée]grale|estuche|obras completas|colecci[óo]n completa|" +
    "colou?ring book|activity book|sticker|annual \\d{4}|calendar|planner|" +
    "study guide|sparknotes|cliffs ?notes|summary of|analysis of|quiz|trivia|" +
    "unofficial|companion|movie storybook|the making of|selections from|" +
    "big book|adventure game|lesson plan|workbook|journal\\s*$|notes\\s*$|" +
    "\\blevel \\d|\\d ?(paperback|hardcover)",
  "i",
)

export const isCompanion = book => COMPANION.test(`${book.title} ${book.subtitle}`)

function checkDigit(twelve) {
  const sum = [...twelve].reduce(
    (total, digit, index) => total + Number(digit) * (index % 2 ? 3 : 1),
    0,
  )
  return (10 - (sum % 10)) % 10
}

export function isbnKey(text) {
  const cleaned = text.replace(/[^0-9a-z]/gi, "").toUpperCase()
  if (cleaned.length !== 10 && cleaned.length !== 13) return undefined
  const twelve =
    cleaned.length === 10 ? "978" + cleaned.slice(0, 9) : cleaned.slice(0, 12)
  if (!/^97[89]\d{9}$/.test(twelve)) return undefined
  if (cleaned.length === 13 && twelve + checkDigit(twelve) !== cleaned) return undefined
  return Number(twelve) - 978_000_000_000
}

export class Catalogue {
  static async load(language, segments, ranks) {
    const catalogue = new Catalogue(language, segments, ranks)
    await catalogue.computeScores()
    return catalogue
  }

  constructor(language, segments, ranks) {
    this.language = language
    this.segments = segments
    this.ranks = ranks
    this.visible = Catalogue.visibility(segments)
    this.workCount = this.visible.reduce((total, bits) => total + bits.size, 0)
    this.tags = segments[0]?.tags ?? []
    this.tagCounts = this.tags.map(tag =>
      segments.reduce((total, segment) => total + (segment.tags[tag.id]?.count ?? 0), 0),
    )
  }

  static visibility(segments) {
    const owners = new Map()
    segments.forEach((segment, index) => {
      segment.tombstones.forEach(work => owners.delete(work))
      segment.works.forEach((work, local) => owners.set(work, [index, local]))
    })
    const bits = segments.map(() => new Set())
    owners.forEach(([index, local]) => bits[index].add(local))
    return bits
  }

  async computeScores() {
    const works = this.allWorks()
    const values = await Promise.all(
      works.map(async work => (await this.ranks?.popularity(work))?.score ?? 0),
    )
    this.scores = new Map(works.map((work, index) => [work, values[index]]))
    const core = this.segments
      .filter(segment => segment.pack === "core")
      .flatMap(segment => [...segment.works])
    this.maxScore = Math.max(1, ...core.map(work => this.score(work)))
  }

  allWorks() {
    return this.segments
      .flatMap((segment, index) =>
        [...this.visible[index]].map(local => segment.works[local]),
      )
      .sort((a, b) => a - b)
  }

  score(work) {
    return this.scores.get(work) ?? 0
  }

  popularity(work) {
    return Math.log(1 + this.score(work)) / Math.log(1 + this.maxScore)
  }

  locate(work) {
    for (const segment of [...this.segments].reverse()) {
      if (contains(segment.tombstones, work)) return undefined
      const local = segment.localOf(work)
      if (local >= 0) return { segment, local }
    }
    return undefined
  }

  async book(work) {
    const location = this.locate(work)
    if (!location) return undefined
    const { segment, local } = location
    const [heads, facts, popularity] = await Promise.all([
      segment.heads(local),
      segment.facts(local),
      this.ranks?.popularity(work),
    ])
    const authors = await Promise.all(facts.authors.map(slot => segment.author(slot)))
    return {
      work,
      ...heads,
      authors: authors.map(({ number, name }) => ({ number, name })),
      author: authors[0]?.name ?? "",
      year: facts.year,
      cover: facts.cover,
      tags: facts.tags,
      series: facts.series,
      popularity,
    }
  }

  async books(works) {
    return (await Promise.all(works.map(work => this.book(work)))).filter(Boolean)
  }

  async bookOfIsbn(isbn) {
    const key = isbnKey(isbn)
    if (key === undefined) return undefined
    for (const segment of [...this.segments].reverse()) {
      const local = await segment.localOfIsbn(key)
      if (local >= 0) return this.book(segment.works[local])
    }
    return undefined
  }

  async description(work) {
    const location = this.locate(work)
    if (!location) return { text: "", translated: false }
    return location.segment.description(location.local)
  }

  async series(work) {
    const location = this.locate(work)
    if (!location) return undefined
    const { series } = await location.segment.facts(location.local)
    return series < 0 ? undefined : location.segment.series(series)
  }

  visibleWorks(segment, locals) {
    const bits = this.visible[this.segments.indexOf(segment)]
    return locals.filter(local => bits.has(local)).map(local => segment.works[local])
  }

  tagWorks(tag) {
    return this.segments.flatMap(segment =>
      this.visibleWorks(segment, segment.tags[tag]?.works() ?? []),
    )
  }

  topWorks(works, limit) {
    return [...works].sort((a, b) => this.score(b) - this.score(a)).slice(0, limit)
  }

  popularWorks(limit, tag) {
    return this.topWorks(
      tag === undefined ? this.scores.keys() : this.tagWorks(tag),
      limit,
    )
  }
}
