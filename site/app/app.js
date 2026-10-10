import { Catalogue, Ranks, Segment } from "./catalogue.js"
import { Searcher, authorBooks, popular } from "./search.js"

const CATALOGUE = "../catalogue/"
const COVERS = "https://covers.openlibrary.org"
const LANGUAGES = { en: "English", de: "Deutsch", fr: "Français", es: "Español" }
const SHELVES = { Reading: "Reading", Want: "Want to read", Read: "Finished" }
const SECTIONS = { audience: "Age group", form: "Book types", topic: "Topics" }

const main = document.querySelector("main")
const searchInput = document.querySelector("#search")
let manifest
let loaded
let navigation = 0

function stored(key, fallback) {
  try {
    return JSON.parse(localStorage.getItem(key)) ?? fallback
  } catch {
    return fallback
  }
}

function store(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {}
}

const browserLanguage = navigator.language.slice(0, 2)
let language = stored("language", browserLanguage in LANGUAGES ? browserLanguage : "en")
let library = stored("library", {})

class Raw {
  constructor(html) {
    this.html = html
  }
}

const escape = text =>
  String(text).replace(/[&<>"']/g, character => `&#${character.charCodeAt(0)};`)

function render(value) {
  if (value === undefined || value === null || value === false) return ""
  if (Array.isArray(value)) return value.map(render).join("")
  return value instanceof Raw ? value.html : escape(value)
}

const html = (strings, ...values) =>
  new Raw(
    strings.reduce((output, part, index) => output + render(values[index - 1]) + part),
  )

const megabytes = bytes => `${(bytes / 1e6).toFixed(1)} MB`

const labelParts = label => label.split("-").map(Number)

function releaseOrder(first, second) {
  const left = labelParts(first)
  const right = labelParts(second)
  const index = left.findIndex((part, position) => part !== right[position])
  return index < 0 ? left.length - right.length : left[index] - (right[index] ?? 0)
}

const segmentOrder = (left, right) =>
  releaseOrder(left.month, right.month) || Number(!left.isBase) - Number(!right.isBase)

async function download(entries, onProgress) {
  let done = 0
  return Promise.all(
    entries.map(async entry => {
      const response = await fetch(CATALOGUE + entry.id + ".bin.gz")
      if (!response.ok) throw Error(`${entry.id}: ${response.status}`)
      const stream = response.body.pipeThrough(new DecompressionStream("gzip"))
      const bytes = new Uint8Array(await new Response(stream).arrayBuffer())
      onProgress((done += entry.size))
      return [entry, bytes]
    }),
  )
}

async function loadCatalogue() {
  manifest ??= await fetch(CATALOGUE + "manifest.json").then(response => response.json())
  const entries = manifest.segments.filter(entry => entry.language === language)
  const total = entries.reduce((sum, entry) => sum + entry.size, 0)
  const files = await download(entries, done => {
    main.innerHTML = render(
      html`<p class="status">
        Loading catalogue · ${megabytes(done)} of ${megabytes(total)}
      </p>`,
    )
  })
  const ranks = files.find(([entry]) => entry.pack === "ranks")
  const segments = files
    .filter(([entry]) => entry.pack !== "ranks")
    .map(([, bytes]) => new Segment(bytes))
    .sort(segmentOrder)
  const catalogue = await Catalogue.load(language, segments, ranks && new Ranks(ranks[1]))
  return { catalogue, searcher: new Searcher(catalogue) }
}

const savedWorks = () => new Set(Object.keys(library).map(Number))

function cover(book, size = "M") {
  const hue = (book.work * 47) % 360
  const image =
    book.cover > 0 &&
    html`<img
      src="${COVERS}/b/id/${book.cover}-${size}.jpg"
      alt=""
      loading="lazy"
      onerror="this.remove()"
    />`
  return html`<div class="cover" style="--hue: ${hue}">
    <span>${book.title}</span>${image}
  </div>`
}

const card = book =>
  html`<a class="card" href="#/book/${book.work}">
    ${cover(book)}<strong>${book.title}</strong><small>${book.author}</small></a
  >`

const row = (title, books, more) =>
  books.length > 0 &&
  html`<section>
    <header class="section">
      <h2>${title}</h2>
      ${more && html`<a href="${more}">See all</a>`}
    </header>
    <div class="row">${books.map(card)}</div>
  </section>`

const grid = books => html`<div class="book-grid">${books.map(card)}</div>`

async function explore({ catalogue }) {
  const books = await popular(catalogue, 16, undefined, savedWorks())
  const tags = catalogue.tags
    .filter(tag => catalogue.tagCounts[tag.id] > 0)
    .sort((a, b) => catalogue.tagCounts[b.id] - catalogue.tagCounts[a.id])
  const ofCategory = category => tags.filter(tag => tag.category === category)
  const link = tag => html`<a href="#/tag/${tag.id}">${tag.label}</a>`
  return html`<h1>Explore</h1>
    ${row("Popular now", books)}
    <h2 class="section">Genres</h2>
    <div class="genres">${ofCategory("genre").map(link)}</div>
    ${Object.entries(SECTIONS).map(
      ([category, title]) =>
        html` <h2 class="section">${title}</h2>
          <div class="tag-list">${ofCategory(category).map(link)}</div>`,
    )}`
}

async function tag({ catalogue }, id) {
  const books = await popular(catalogue, 90, Number(id))
  return html`<h1>${catalogue.tags[id]?.label}</h1>
    ${grid(books)}`
}

async function search({ catalogue, searcher }, query) {
  if (!query.trim()) return html`<p class="status">Search titles, authors or ISBNs.</p>`
  const byIsbn = await catalogue.bookOfIsbn(query)
  const [books, authors] = await Promise.all([
    byIsbn ? [byIsbn] : searcher.search(query),
    searcher.authors(query),
  ])
  const suggestions = books.length ? [] : searcher.complete(query)
  const authorLink = author =>
    html`<a
      class="author-chip"
      href="#/author/${author.number}/${encodeURIComponent(author.name)}"
    >
      ${authorPhoto(author)}${author.name}</a
    >`
  const suggestion = text =>
    html`<a href="#/search/${encodeURIComponent(text)}">${text}</a>`
  return html` ${authors.length > 0 &&
  html`<div class="tag-list">${authors.map(authorLink)}</div>`}
  ${books.length
    ? grid(books)
    : html`<p class="status">
        No books found.
        ${suggestions.length > 0 && html`Try ${suggestions.map(suggestion)}`}
      </p>`}`
}

const authorPhoto = author =>
  html`<span class="avatar">
    <img
      src="${COVERS}/a/olid/OL${author.number}A-S.jpg?default=false"
      alt=""
      loading="lazy"
      onerror="this.remove()"
  /></span>`

function shelfButtons(book) {
  const current = library[book.work]?.shelf
  return html`<div class="shelves">
    ${Object.entries(SHELVES).map(
      ([shelf, label]) =>
        html`<button
          data-shelf="${shelf}"
          data-work="${book.work}"
          class="${shelf === current ? "active" : ""}"
        >
          ${label}
        </button>`,
    )}
  </div>`
}

function stats(book) {
  const popularity = book.popularity
  const parts = [book.year || ""]
  if (popularity?.ratings) {
    parts.push(
      `★ ${popularity.rating.toFixed(1)} (${popularity.ratings.toLocaleString()})`,
    )
  }
  if (popularity?.readers) parts.push(`${popularity.readers.toLocaleString()} readers`)
  return parts.filter(Boolean).join(" · ")
}

async function book({ catalogue }, work) {
  const found = await catalogue.book(Number(work))
  if (!found) return html`<p class="status">Not in this catalogue.</p>`
  const [description, series] = await Promise.all([
    catalogue.description(found.work),
    catalogue.series(found.work),
  ])
  const members = series ? await catalogue.books(series.members) : []
  const author = found.authors[0]
  const exclude = new Set([found.work, ...(series?.members ?? [])])
  const more = author ? await authorBooks(catalogue, author, 12, exclude) : []
  const authorLink = author =>
    html`<a href="#/author/${author.number}/${encodeURIComponent(author.name)}"
      >${author.name}</a
    >`
  const paragraphs = description.text.split(/\n\s*\n/).filter(Boolean)
  return html`<article class="book">
      ${cover(found, "L")}
      <div>
        <h1>${found.title}</h1>
        ${found.subtitle && html`<p class="subtitle">${found.subtitle}</p>`}
        <p class="authors">${found.authors.map(authorLink)}</p>
        <p class="muted">${stats(found)}</p>
        ${shelfButtons(found)}
        <div class="tag-list">
          ${found.tags.map(
            id =>
              catalogue.tags[id] &&
              html`<a href="#/tag/${id}">${catalogue.tags[id].label}</a>`,
          )}
        </div>
      </div>
    </article>
    ${paragraphs.length > 0 &&
    html`<section class="about">
      <h2>About this book</h2>
      ${paragraphs.map(text => html`<p>${text}</p>`)}
      ${description.translated && html`<p class="muted">Machine translated</p>`}
    </section>`}
    ${members.length > 1 && row(series.name, members)}
    ${author &&
    row(
      `More by ${author.name}`,
      more,
      `#/author/${author.number}/${encodeURIComponent(author.name)}`,
    )}
    <p class="muted">
      <a href="https://openlibrary.org/works/OL${found.work}W"> View on Open Library</a>
    </p>`
}

async function author({ catalogue }, number, name) {
  const person = { number: Number(number), name }
  const books = await authorBooks(catalogue, person, 90)
  return html`<header class="person">
      ${authorPhoto(person)}
      <div>
        <h1>${person.name}</h1>
        <p class="muted">${books.length} books</p>
      </div>
    </header>
    ${grid(books)}`
}

function libraryView() {
  const entries = Object.values(library).sort((a, b) => b.added - a.added)
  const shelves = Object.entries(SHELVES).map(([shelf, label]) =>
    row(
      label,
      entries.filter(entry => entry.shelf === shelf),
    ),
  )
  if (!entries.length) {
    return html`<h1>Library</h1>
      <p class="status">Nothing here yet. Find a book and add it to a shelf.</p>`
  }
  return html`<h1>Library</h1>
    ${shelves}
    <p class="muted">Saved in this browser only.</p>`
}

function settings() {
  const languageOption = ([code, label]) =>
    html`<option value="${code}" ${code === language ? "selected" : ""}>${label}</option>`
  return html`<h1>Settings</h1>
    <h2 class="section">Catalogue language</h2>
    <select id="language">
      ${Object.entries(LANGUAGES).map(languageOption)}
    </select>
    <p class="muted">
      Core catalogue ${manifest?.month ?? ""} · Data from Open Library. Loads from this
      site, nothing else leaves your browser. All genre packs: Shelf for Android.
    </p>`
}

const routes = {
  "": explore,
  tag,
  search,
  book,
  author,
  library: libraryView,
  settings,
}

async function route() {
  const [name = "", ...parameters] = location.hash.replace(/^#\/?/, "").split("/")
  const view = routes[name] ?? explore
  const current = ++navigation
  const query = name === "search" ? decodeURIComponent(parameters.join("/")) : ""
  if (searchInput.value !== query) searchInput.value = query
  document
    .querySelectorAll("nav.tabs a")
    .forEach(link =>
      link.classList.toggle("active", link.getAttribute("href") === `#/${name}`),
    )
  try {
    loaded ??= loadCatalogue()
    const content = await view(await loaded, ...parameters.map(decodeURIComponent))
    if (current !== navigation) return
    main.innerHTML = render(content)
    if (name !== "search") scrollTo(0, 0)
  } catch (error) {
    loaded = undefined
    main.innerHTML = render(
      html`<p class="status">Could not load the catalogue. ${error.message}</p>`,
    )
  }
}

async function setShelf(button) {
  const work = Number(button.dataset.work)
  const { shelf } = button.dataset
  if (library[work]?.shelf === shelf) delete library[work]
  else library[work] = await entryOf(work, shelf)
  store("library", library)
  route()
}

async function entryOf(work, shelf) {
  const { catalogue } = await loaded
  const { title, author, cover } = await catalogue.book(work)
  return {
    work,
    shelf,
    title,
    author,
    cover,
    added: library[work]?.added ?? Date.now(),
  }
}

main.addEventListener("click", event => {
  const button = event.target.closest("[data-shelf]")
  if (button) setShelf(button)
})

main.addEventListener("change", event => {
  if (event.target.id !== "language") return
  language = event.target.value
  store("language", language)
  loaded = undefined
  route()
})

let typing
searchInput.addEventListener("input", () => {
  clearTimeout(typing)
  typing = setTimeout(() => {
    const target = `#/search/${encodeURIComponent(searchInput.value)}`
    if (location.hash.startsWith("#/search")) history.replaceState(null, "", target)
    else history.pushState(null, "", target)
    route()
  }, 200)
})

document.querySelector("#theme-toggle").addEventListener("click", () => {
  const root = document.documentElement
  root.dataset.theme = root.dataset.theme === "dark" ? "light" : "dark"
  try {
    localStorage.setItem("theme", root.dataset.theme)
  } catch {}
})

addEventListener("hashchange", route)
route()
