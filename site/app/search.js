import { isCompanion } from "./catalogue.js"

const AUTHOR_LIMIT = 150
const RERANK_DEPTH = 250
const PREFIX_EXPANSIONS = 12
const FUZZY_CANDIDATES = 60
const AUTHOR_FIELD = 0.85
const EXACT = 1
const PREFIX = 0.5
const PHRASE = 0.4
const LENGTH = 0.04
const COVERAGE = 0.4
const COMBO = 0.5
const AUTHOR = 1
const POPULARITY = 0.6
const ALTERNATE = 0.6
const COMPANION_PENALTY = 0.7
const SERIES_START = 0.9
const RELEVANCE_FLOOR = 0.45
const ANCHOR = 0.8
const LONG_COMPLETION = 0.9
const SHORT_COMPLETION = 0.75
const SPARSE_EXACT = 50
const SPARSE_COMPLETIONS = 20
const ONE_TYPO = 0.72
const TWO_TYPOS = 0.5
const RANK_POPULARITY = 0.35
const MAX_TOKEN_LENGTH = 24
export const ARTICLES = new Set([
  "the",
  "a",
  "an",
  "der",
  "die",
  "das",
  "le",
  "la",
  "les",
  "el",
  "los",
  "las",
])
const ARTICLE = new RegExp(`^(${[...ARTICLES].join("|")}) `)
const TITLE_BREAK = /[:;(/]/

const FOLD = new Map(
  [
    ["áàâäãåÁÀÂÄÃÅ", "a"],
    ["éèêëÉÈÊË", "e"],
    ["íìîïÍÌÎÏ", "i"],
    ["óòôöõøÓÒÔÖÕØ", "o"],
    ["úùûüÚÙÛÜ", "u"],
    ["ñÑ", "n"],
    ["çÇ", "c"],
    ["ýÿÝ", "y"],
    ["ß", "s"],
  ].flatMap(([characters, replacement]) =>
    [...characters].map(character => [character, replacement]),
  ),
)

function fold(character) {
  const lowered = character.toLowerCase()
  if (/^[a-z0-9]$/.test(lowered)) return lowered
  return FOLD.get(character)
}

export function tokenize(text) {
  const tokens = []
  let current = ""
  for (const character of text) {
    if (/['’̀-ͯ]/.test(character)) continue
    const folded = fold(character)
    if (folded) current += folded
    if ((!folded || current.length >= MAX_TOKEN_LENGTH) && current) {
      tokens.push(current)
      current = ""
    }
  }
  if (current) tokens.push(current)
  return tokens
}

export const mainTitle = title => title.split(TITLE_BREAK)[0]

const withoutArticle = text => text.replace(ARTICLE, "")

export function editDistance(left, right, limit) {
  if (Math.abs(left.length - right.length) > limit) return limit + 1
  let beforePrevious
  let previous = Array.from({ length: right.length + 1 }, (_, index) => index)
  for (let i = 1; i <= left.length; i++) {
    const current = [i]
    for (let j = 1; j <= right.length; j++) {
      const cost = left[i - 1] === right[j - 1] ? 0 : 1
      current[j] = Math.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
      const swapped =
        i > 1 && j > 1 && left[i - 1] === right[j - 2] && left[i - 2] === right[j - 1]
      if (swapped && beforePrevious) {
        current[j] = Math.min(current[j], beforePrevious[j - 2] + 1)
      }
    }
    if (Math.min(...current) > limit) return limit + 1
    beforePrevious = previous
    previous = current
  }
  return previous.at(-1)
}

const allowedTypos = token => (token.length <= 3 ? 0 : token.length <= 6 ? 1 : 2)

const transpositions = token =>
  Array.from(
    { length: token.length - 1 },
    (_, index) =>
      token.slice(0, index) + token[index + 1] + token[index] + token.slice(index + 2),
  )

function trigrams(term) {
  const padded = `$${term}$`
  return Array.from({ length: padded.length - 2 }, (_, index) =>
    padded.slice(index, index + 3),
  )
}

const sum = (values, select) => values.reduce((total, value) => total + select(value), 0)

export class Searcher {
  constructor(catalogue) {
    this.catalogue = catalogue
    this.segments = catalogue.segments
    this.total = catalogue.ranks?.works ?? catalogue.workCount
  }

  segmentHits(segment, variants, needed) {
    const hits = new Map()
    for (const variant of variants) {
      const variantHits = new Map()
      for (const gram of new Set(trigrams(variant))) {
        for (const term of segment.gramTerms(gram)) {
          variantHits.set(term, (variantHits.get(term) ?? 0) + 1)
        }
      }
      variantHits.forEach((count, term) =>
        hits.set(term, Math.max(hits.get(term) ?? 0, count)),
      )
    }
    return [...hits]
      .filter(([, count]) => count >= needed)
      .sort((a, b) => b[1] - a[1])
      .slice(0, FUZZY_CANDIDATES)
      .map(([term]) => segment.termById(term).text)
  }

  fuzzyTerms(token) {
    const limit = allowedTypos(token)
    if (!limit) return []
    const needed = Math.max(1, trigrams(token).length - 3 * limit)
    const variants = [token, ...transpositions(token)]
    const texts = new Set(
      this.segments.flatMap(segment => this.segmentHits(segment, variants, needed)),
    )
    return [...texts]
      .map(text => [text, editDistance(token, text, limit)])
      .filter(([text, distance]) => text !== token && distance <= limit)
  }

  frequency(text) {
    return (
      this.catalogue.ranks?.frequency(text) ??
      sum(this.segments, segment => segment.term(text)?.frequency ?? 0)
    )
  }

  completions(prefix) {
    const texts = new Set(
      this.segments.flatMap(segment =>
        segment.completions(prefix, PREFIX_EXPANSIONS).map(term => term.text),
      ),
    )
    return [...texts]
      .sort((a, b) => this.frequency(b) - this.frequency(a))
      .slice(0, PREFIX_EXPANSIONS)
  }

  termMatches(token, isLast) {
    const matches = []
    const exactFrequency = sum(
      this.segments,
      segment => segment.term(token)?.frequency ?? 0,
    )
    if (exactFrequency > 0) matches.push({ text: token, weight: 1 })
    let completionFrequency = 0
    if (isLast && token.length >= 2) {
      const weight = token.length >= 4 ? LONG_COMPLETION : SHORT_COMPLETION
      for (const text of this.completions(token)) {
        completionFrequency += this.frequency(text)
        matches.push({ text, weight })
      }
    }
    const sparse =
      exactFrequency < SPARSE_EXACT &&
      (completionFrequency < SPARSE_COMPLETIONS || exactFrequency === 0)
    if (!sparse) return matches
    for (const [text, distance] of this.fuzzyTerms(token)) {
      matches.push({ text, weight: distance === 1 ? ONE_TYPO : TWO_TYPOS })
    }
    return matches
  }

  async tokenScores(token, matches) {
    const scores = new Map()
    const terms = new Map()
    const offer = (work, value, text) => {
      if (value <= (scores.get(work) ?? 0)) return
      scores.set(work, value)
      terms.set(work, text)
    }
    let authors = 0
    for (const match of matches) {
      for (const segment of this.segments) {
        const term = segment.term(match.text)
        if (!term) continue
        for (const work of this.catalogue.visibleWorks(segment, term.titleWorks())) {
          offer(work, match.weight, match.text)
        }
        const slots = term.authorSlots().slice(0, Math.max(0, AUTHOR_LIMIT - authors))
        authors += slots.length
        const records = await Promise.all(slots.map(slot => segment.author(slot)))
        for (const record of records) {
          for (const work of this.catalogue.visibleWorks(segment, record.works)) {
            offer(work, match.weight * AUTHOR_FIELD, match.text)
          }
        }
      }
    }
    const documents = Math.max(
      scores.size,
      sum(matches, match => this.frequency(match.text)),
    )
    const idf = Math.log((this.total + 1) / (Math.min(documents, this.total) + 1)) + 1
    return { token, scores, terms, idf }
  }

  candidates(perToken) {
    const totalIdf = sum(perToken, entry => entry.idf)
    const works = new Set(perToken.flatMap(entry => [...entry.scores.keys()]))
    const result = new Map()
    for (const work of works) {
      let matched = 0
      let anchors = 0
      let text = 0
      for (const entry of perToken) {
        const value = entry.scores.get(work)
        if (value === undefined) continue
        matched++
        if (value >= ANCHOR) anchors++
        text += value * entry.idf
      }
      const coverage = matched / perToken.length
      const lacksAnchor = anchors === 0 && perToken.length > 1
      result.set(work, lacksAnchor ? 0 : (text / totalIdf) * coverage * coverage)
    }
    return result
  }

  async seriesBonus(book, query) {
    const series = await this.catalogue.series(book.work)
    if (!series || tokenize(series.name).join(" ") !== query) return 0
    return series.members[0] === book.work ? SERIES_START : 0
  }

  bonus(book, perToken) {
    const query = perToken.map(entry => entry.terms.get(book.work) ?? entry.token)
    const confidence =
      sum(perToken, entry => entry.scores.get(book.work) ?? 0) / perToken.length
    const lastToken = perToken.at(-1).token
    const last = query.at(-1)
    const completing =
      last !== lastToken && (last.startsWith(lastToken) || lastToken.startsWith(last))
    const joinedQuery = query.join(" ")
    const titles = [
      [book.title, 1],
      [book.alternate, ALTERNATE],
    ].filter(([text]) => text)
    const bestTitle = Math.max(
      0,
      ...titles.map(
        ([text, discount]) =>
          titleValue(text, book.subtitle, query, joinedQuery, completing) * discount,
      ),
    )
    const words = new Set(tokenize(`${book.title} ${book.subtitle} ${book.alternate}`))
    const authorTokens = new Set(book.authors.flatMap(author => tokenize(author.name)))
    const titleHits = query.filter(token => words.has(token)).length
    const authorHits = query.filter(token => authorTokens.has(token)).length
    let bonus = bestTitle * confidence
    bonus += COVERAGE * Math.min(1, (titleHits + authorHits) / query.length)
    if (
      titleHits >= 1 &&
      titleHits < query.length &&
      query.length <= titleHits + authorHits
    ) {
      bonus += COMBO
    }
    if (authorHits === query.length && titleHits < query.length)
      bonus += AUTHOR * confidence
    return bonus
  }

  async search(text, limit = 30) {
    const tokens = tokenize(text)
    if (!tokens.length) return []
    const perToken = await Promise.all(
      tokens.map((token, position) =>
        this.tokenScores(token, this.termMatches(token, position === tokens.length - 1)),
      ),
    )
    const scored = this.candidates(perToken)
    const popularity = work => this.catalogue.popularity(work)
    const head = [...scored.keys()]
      .map(work => [work, scored.get(work) + RANK_POPULARITY * popularity(work)])
      .sort((a, b) => b[1] - a[1])
      .slice(0, RERANK_DEPTH)
      .map(([work]) => work)
    const joined = tokens.join(" ")
    const books = await this.catalogue.books(head)
    const ranked = await Promise.all(
      books.map(async book => {
        const matched =
          perToken.filter(entry => entry.scores.has(book.work)).length / perToken.length
        const boost = (RANK_POPULARITY + POPULARITY) * popularity(book.work) * matched
        const penalty = isCompanion(book) ? COMPANION_PENALTY : 0
        const score =
          scored.get(book.work) +
          this.bonus(book, perToken) +
          boost +
          (await this.seriesBonus(book, joined)) -
          penalty
        return [book, score]
      }),
    )
    ranked.sort((a, b) => b[1] - a[1])
    const best = ranked[0]?.[1] ?? 0
    return ranked
      .filter(([, score]) => score >= RELEVANCE_FLOOR * best)
      .slice(0, limit)
      .map(([book]) => book)
  }

  complete(text, limit = 3) {
    const tokens = tokenize(text)
    const last = tokens.at(-1)
    if (!last || last.length < 2) return []
    const head = tokens.slice(0, -1).join(" ")
    const completions = this.completions(last)
    const words = completions.length
      ? completions
      : this.fuzzyTerms(last)
          .sort((a, b) => a[1] - b[1])
          .map(([word]) => word)
    return words.slice(0, limit).map(word => (head ? `${head} ${word}` : word))
  }

  async authors(text, limit = 3) {
    const tokens = tokenize(text)
    if (!tokens.length) return []
    const perToken = await Promise.all(
      tokens.map(async (token, position) => {
        const texts = this.termMatches(token, position === tokens.length - 1).map(
          match => match.text,
        )
        const records = await Promise.all(
          this.segments.flatMap(segment =>
            texts.flatMap(word =>
              (segment.term(word)?.authorSlots() ?? [])
                .slice(0, AUTHOR_LIMIT)
                .map(slot => segment.author(slot)),
            ),
          ),
        )
        return new Map(
          records.map(record => [
            record.number,
            { number: record.number, name: record.name },
          ]),
        )
      }),
    )
    const common = [...perToken[0].keys()].filter(number =>
      perToken.every(authors => authors.has(number)),
    )
    return common
      .map(number => perToken[0].get(number))
      .sort((a, b) => tokenize(a.name).length - tokenize(b.name).length)
      .slice(0, limit)
  }
}

const MAX_PER_AUTHOR = 2

function titleKey(book) {
  const tokens = tokenize(mainTitle(book.title))
  const trimmed = ARTICLES.has(tokens[0]) ? tokens.slice(1) : tokens
  return `${trimmed.join(" ")}|${book.author}`
}

async function firstUnread(catalogue, work, library) {
  const members = (await catalogue.series(work))?.members ?? [work]
  return members.find(member => !library.has(member) && catalogue.locate(member)) ?? work
}

export async function popular(catalogue, limit, tag, library = new Set()) {
  const works = catalogue.popularWorks(limit * 6, tag)
  const candidates = await Promise.all(
    works.map(async work => catalogue.book(await firstUnread(catalogue, work, library))),
  )
  const series = new Set()
  const titles = new Set()
  const perAuthor = new Map()
  const picked = []
  for (const book of candidates) {
    if (!book || isCompanion(book) || library.has(book.work)) continue
    const name = book.series >= 0 && (await catalogue.series(book.work))?.name
    const count = perAuthor.get(book.author) ?? 0
    const key = titleKey(book)
    if (series.has(name) || count >= MAX_PER_AUTHOR || titles.has(key)) continue
    if (name) series.add(name)
    titles.add(key)
    perAuthor.set(book.author, count + 1)
    picked.push(book)
    if (picked.length === limit) break
  }
  return picked
}

export async function authorWorks(catalogue, author) {
  const token = tokenize(author.name).sort((a, b) => b.length - a.length)[0]
  if (!token) return []
  const works = await Promise.all(
    catalogue.segments.map(async segment => {
      const slots = segment.term(token)?.authorSlots() ?? []
      const records = await Promise.all(slots.map(slot => segment.author(slot)))
      return records
        .filter(record => record.number === author.number)
        .flatMap(record => catalogue.visibleWorks(segment, record.works))
    }),
  )
  return [...new Set(works.flat())]
}

export async function authorBooks(catalogue, author, limit, exclude = new Set()) {
  const works = (await authorWorks(catalogue, author)).filter(work => !exclude.has(work))
  const books = await catalogue.books(catalogue.topWorks(works, limit * 3))
  const titles = new Set()
  return books
    .filter(book => {
      const key = titleKey(book)
      if (isCompanion(book) || titles.has(key)) return false
      titles.add(key)
      return true
    })
    .slice(0, limit)
}

function titleValue(title, subtitle, query, joinedQuery, completing) {
  const joined = tokenize(title).join(" ")
  const full = tokenize(`${title} ${subtitle}`).join(" ")
  const main = tokenize(mainTitle(title))
  const titles = new Set([withoutArticle(joined), withoutArticle(main.join(" "))])
  const value =
    titles.has(withoutArticle(joinedQuery)) && !completing
      ? EXACT
      : joined.startsWith(joinedQuery)
        ? PREFIX
        : ` ${full} `.includes(` ${joinedQuery} `)
          ? PHRASE
          : 0
  return value - Math.min(LENGTH * Math.max(0, main.length - query.length), 0.3)
}
