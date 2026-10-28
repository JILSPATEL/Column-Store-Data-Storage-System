#pragma once
#include <algorithm>
#include <bitset>
#include <cctype>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <memory>
#include <regex>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>
#include <cerrno>
#include <sys/stat.h>
#include <unistd.h>


namespace cdb {
inline std::regex rx(const std::string &p) {
  return std::regex(p, std::regex_constants::icase);
}
namespace fs {
class path {
  std::string value;
public:
  path() = default;
  path(const char *v) : value(v) {}
  path(const std::string &v) : value(v) {}
  path operator/(const std::string &part) const {
    return path(value.empty() ? part : value + "/" + part);
  }
  path operator/(const char *part) const { return operator/(std::string(part)); }
  path operator/(const path &part) const { return operator/(part.value); }
  std::string string() const { return value; }
  std::string filename() const {
    const auto p = value.find_last_of("/\\");
    return p == std::string::npos ? value : value.substr(p + 1);
  }
  std::string extension() const {
    const auto n = filename();
    const auto p = n.find_last_of('.');
    return p == std::string::npos ? "" : n.substr(p);
  }
  operator std::string() const { return value; }
};
inline bool exists(const path &p) {
  struct stat s;
  return stat(p.string().c_str(), &s) == 0;
}
inline bool is_directory(const path &p) {
  struct stat s;
  return stat(p.string().c_str(), &s) == 0 && S_ISDIR(s.st_mode);
}
inline void create_directories(const path &p) {
  if (p.string().empty() || exists(p)) return;
  const auto parent = p.string().find_last_of("/\\");
  if (parent != std::string::npos) create_directories(p.string().substr(0, parent));
  mkdir(p.string().c_str());
}
inline bool remove(const path &p, std::error_code &ec) {
  const bool ok = is_directory(p) ? rmdir(p.string().c_str()) == 0
                                  : unlink(p.string().c_str()) == 0;
  if (!ok) ec = std::error_code(errno, std::generic_category());
  return ok;
}
inline void remove_all(const path &p, std::error_code &ec) {
  if (!is_directory(p)) {
    remove(p, ec);
    return;
  }
  DIR *dir = opendir(p.string().c_str());
  if (dir) {
    struct dirent *entry;
    while ((entry = readdir(dir)) != nullptr) {
      const std::string name = entry->d_name;
      if (name != "." && name != "..") remove_all(p / name, ec);
    }
    closedir(dir);
  }
  remove(p, ec);
}
struct directory_entry {
  path value;
  path path() const { return value; }
  bool is_directory() const { return fs::is_directory(value); }
};
class directory_iterator {
  std::vector<directory_entry> entries;
  std::size_t current;
public:
  explicit directory_iterator(const path &p) {
    DIR *dir = opendir(p.string().c_str());
    if (dir) {
      struct dirent *entry;
      while ((entry = readdir(dir)) != nullptr) {
        const std::string name = entry->d_name;
        if (name != "." && name != "..") entries.push_back({p / name});
      }
      closedir(dir);
    }
    current = 0;
  }
  directory_iterator() : current(0) {}
  const directory_entry &operator*() const { return entries[current]; }
  directory_iterator &operator++() { ++current; return *this; }
  bool operator!=(const directory_iterator &other) const {
    return current != other.current || entries.size() != other.entries.size();
  }
  directory_iterator begin() { return *this; }
  directory_iterator end() { directory_iterator result; result.entries = entries; result.current = entries.size(); return result; }
};
}
inline std::string trim(std::string s) {
  auto a = s.find_first_not_of(" \t\r\n"), b = s.find_last_not_of(" \t\r\n");
  return a == std::string::npos ? "" : s.substr(a, b - a + 1);
}
inline std::string upper(std::string s) {
  for (char &c : s)
    c = (char)std::toupper((unsigned char)c);
  return s;
}
inline std::string unquote(std::string s) {
  s = trim(s);
  if (s.size() >= 2 && ((s.front() == '\'' && s.back() == '\'') ||
                        (s.front() == '"' && s.back() == '"')))
    return s.substr(1, s.size() - 2);
  return s;
}
inline std::vector<std::string> split_csv(const std::string &s) {
  std::vector<std::string> r;
  std::string x;
  char quote = 0;
  int depth = 0;
  for (char c : s) {
    if ((c == '\'' || c == '"') && (!quote || quote == c))
      quote = quote ? 0 : c;
    if (!quote && c == '(')
      ++depth;
    if (!quote && c == ')')
      --depth;
    if (c == ',' && !quote && !depth) {
      r.push_back(trim(x));
      x.clear();
    } else
      x += c;
  }
  r.push_back(trim(x));
  return r;
}

struct Column {
  std::string name, type;
  std::vector<std::string> constraints;
  bool categorical() const {
    auto t = upper(type);
    return t == "STRING" || t == "VARCHAR" || t == "TEXT" || t == "CHAR" ||
           t == "BOOLEAN" || t == "BOOL";
  }
  bool has(const std::string &c) const {
    auto u = upper(c);
    for (auto &x : constraints)
      if (upper(x) == u)
        return true;
    return false;
  }
};
struct Schema {
  std::string name;
  std::vector<Column> columns;
  Column *column(const std::string &n) {
    for (auto &c : columns)
      if (c.name == n)
        return &c;
    return nullptr;
  }
  const Column *column(const std::string &n) const {
    for (auto &c : columns)
      if (c.name == n)
        return &c;
    return nullptr;
  }
};

class SchemaManager {
  fs::path dir;
  std::map<std::string, Schema> schemas;

public:
  explicit SchemaManager(const fs::path &root) : dir(root / "metadata") {
    fs::create_directories(dir);
    load();
  }
  void load() {
    for (auto &e : fs::directory_iterator(dir))
      if (e.path().extension() == ".schema") {
        std::ifstream f(e.path());
        std::string s((std::istreambuf_iterator<char>(f)), {});
        std::istringstream tok(s);
        std::vector<std::string> a;
        std::string z;
        while (tok >> z)
          a.push_back(z);
        if (a.size() < 2 || a[0] != "TABLE")
          continue;
        Schema t;
        t.name = a[1];
        for (size_t i = 2; i < a.size();) {
          if (a[i++] != "COLUMN" || i + 1 >= a.size())
            break;
          Column c;
          c.name = a[i++];
          c.type = a[i++];
          while (i < a.size() && a[i] != "COLUMN")
            c.constraints.push_back(a[i++]);
          t.columns.push_back(c);
        }
        schemas[t.name] = t;
      }
  }
  void create(const Schema &s) {
    fs::create_directories(dir);
    std::ofstream f(dir / (s.name + ".schema"));
    f << "TABLE " << s.name;
    for (auto &c : s.columns) {
      f << " COLUMN " << c.name << " " << c.type;
      for (auto &x : c.constraints)
        f << " " << x;
    }
    schemas[s.name] = s;
  }
  void drop(const std::string &n) {
    schemas.erase(n);
    std::error_code ec;
    fs::remove(dir / (n + ".schema"), ec);
  }
  Schema *get(const std::string &n) {
    auto i = schemas.find(n);
    return i == schemas.end() ? nullptr : &i->second;
  }
  const std::map<std::string, Schema> &all() const { return schemas; }
};

inline void put32(std::ostream &o, uint32_t v) {
  for (int i = 3; i >= 0; --i)
    o.put((char)(v >> (i * 8)));
}
inline uint32_t get32(std::istream &i) {
  uint32_t v = 0;
  for (int n = 0; n < 4; ++n)
    v = (v << 8) | (unsigned char)i.get();
  return v;
}
inline void put16(std::ostream &o, uint16_t v) {
  o.put((char)(v >> 8));
  o.put((char)v);
}
inline uint16_t get16(std::istream &i) {
  return (uint16_t)(((unsigned char)i.get() << 8) | (unsigned char)i.get());
}
inline void put64(std::ostream &o, uint64_t v) {
  for (int i = 7; i >= 0; --i)
    o.put((char)(v >> (i * 8)));
}
inline uint64_t get64(std::istream &i) {
  uint64_t v = 0;
  for (int n = 0; n < 8; ++n)
    v = (v << 8) | (unsigned char)i.get();
  return v;
}
inline int tag(const std::string &t) {
  auto u = upper(t);
  if (u == "BYTE")
    return 1;
  if (u == "SHORT")
    return 2;
  if (u == "INT" || u == "INTEGER")
    return 3;
  if (u == "LONG" || u == "BIGINT")
    return 4;
  if (u == "FLOAT" || u == "REAL")
    return 5;
  if (u == "DOUBLE" || u == "DECIMAL")
    return 6;
  if (u == "BOOLEAN" || u == "BOOL")
    return 7;
  if (u == "BIGDECIMAL" || u == "NUMERIC")
    return 8;
  return 9;
}
inline int width(int t) {
  return t == 1 || t == 7   ? 1
         : t == 2           ? 2
         : t == 3 || t == 5 ? 4
         : t == 4 || t == 6 ? 8
         : t == 8           ? 20
                            : 4;
}

class Storage {
  fs::path root;
  fs::path path(const std::string &t, const std::string &c) const {
    return root / "tables" / t / (c + ".bin");
  }
  fs::path dict(const std::string &t, const std::string &c) const {
    return root / "tables" / t / (c + ".dict");
  }
  std::vector<std::string> loadDict(const std::string &t,
                                    const std::string &c) const {
    std::vector<std::string> d;
    std::ifstream f(dict(t, c), std::ios::binary);
    if (!f)
      return d;
    auto n = get32(f);
    for (uint32_t k = 0; k < n; ++k) {
      auto l = get16(f);
      std::string s(l, '\0');
      f.read(&s[0], l);
      d.push_back(s);
    }
    return d;
  }
  void saveDict(const std::string &t, const std::string &c,
                const std::vector<std::string> &d) {
    std::ofstream f(dict(t, c), std::ios::binary);
    put32(f, (uint32_t)d.size());
    for (auto &s : d) {
      put16(f, (uint16_t)s.size());
      f.write(s.data(), s.size());
    }
  }

public:
  explicit Storage(const fs::path &r) : root(r) {
    fs::create_directories(root / "tables");
  }
  void create(const Schema &s) {
    fs::create_directories(root / "tables" / s.name);
    for (auto &c : s.columns) {
      std::ofstream f(path(s.name, c.name), std::ios::binary);
      put32(f, tag(c.type));
      put32(f, 0);
      if (tag(c.type) == 9)
        saveDict(s.name, c.name, {});
    }
  }
  std::vector<std::string> read(const std::string &t,
                                const std::string &c) const {
    std::vector<std::string> r;
    std::ifstream f(path(t, c), std::ios::binary);
    if (!f)
      return r;
    int tg = get32(f);
    auto n = get32(f);
    auto d = tg == 9 ? loadDict(t, c) : std::vector<std::string>();
    for (uint32_t i = 0; i < n; ++i) {
      int flag = f.get();
      if (flag == 255) {
        f.seekg(width(tg), std::ios::cur);
        continue;
      }
      if (tg == 9) {
        auto id = get32(f);
        r.push_back(id < d.size() ? d[id] : "null");
      } else if (tg == 1)
        r.push_back(std::to_string((int8_t)f.get()));
      else if (tg == 2)
        r.push_back(std::to_string((int16_t)((f.get() << 8) | f.get())));
      else if (tg == 3)
        r.push_back(std::to_string((int32_t)get32(f)));
      else if (tg == 4)
        r.push_back(std::to_string((int64_t)get64(f)));
      else if (tg == 7)
        r.push_back(f.get() ? "true" : "false");
      else if (tg == 5) {
        uint32_t x = get32(f);
        float v;
        std::memcpy(&v, &x, 4);
        std::ostringstream o;
        o << v;
        r.push_back(o.str());
      } else if (tg == 6) {
        uint64_t x = get64(f);
        double v;
        std::memcpy(&v, &x, 8);
        std::ostringstream o;
        o << v;
        r.push_back(o.str());
      } else {
        int scale = (int32_t)get32(f);
        int64_t un = (int64_t)get64(f);
        get64(f);
        double v = un;
        for (int j = 0; j < scale; ++j)
          v /= 10;
        r.push_back(std::to_string(v));
      }
    }
    return r;
  }
  void append(const std::string &t, const Column &c, const std::string &value) {
    auto p = path(t, c.name);
    std::fstream f(p, std::ios::in | std::ios::out | std::ios::binary);
    f.seekg(0);
    int tg = get32(f);
    auto n = get32(f);
    f.seekp(0, std::ios::end);
    f.put(0);
    std::string v = unquote(value);
    if (tg == 9) {
      auto d = loadDict(t, c.name);
      auto it = std::find(d.begin(), d.end(), v);
      uint32_t id =
          it == d.end() ? (d.push_back(v), d.size() - 1) : it - d.begin();
      put32(f, id);
      saveDict(t, c.name, d);
    } else {
      if (tg == 1)
        f.put((char)std::stoi(v));
      else if (tg == 2) {
        auto x = std::stoi(v);
        f.put(x >> 8);
        f.put(x);
      } else if (tg == 3)
        put32(f, std::stol(v));
      else if (tg == 4)
        put64(f, std::stoll(v));
      else if (tg == 7)
        f.put((char)(upper(v) == "TRUE" || v == "1"));
      else if (tg == 5) {
        float x = std::stof(v);
        uint32_t b;
        std::memcpy(&b, &x, 4);
        put32(f, b);
      } else if (tg == 6) {
        double x = std::stod(v);
        uint64_t b;
        std::memcpy(&b, &x, 8);
        put64(f, b);
      } else {
        double x = std::stod(v);
        put32(f, 0);
        put64(f, (int64_t)x);
        put64(f, 0);
      }
    }
    f.seekp(4);
    put32(f, n + 1);
  }
  void update(const std::string &t, const Column &c, int row,
              const std::string &v) {
    auto p = path(t, c.name);
    std::fstream f(p, std::ios::in | std::ios::out | std::ios::binary);
    int tg = get32(f), n = get32(f), live = 0, phys = -1;
    for (int i = 0; i < n; ++i) {
      int fl = f.get();
      if (fl != 255) {
        if (live++ == row)
          phys = i;
      }
      f.seekg(width(tg), std::ios::cur);
    }
    if (phys < 0)
      return; // rewrite through a temporary in-place record encoding
    f.clear();
    f.seekp(8 + (std::streamoff)phys * (1 + width(tg)) + 1);
    std::string x = unquote(v);
    if (tg == 9) {
      auto d = loadDict(t, c.name);
      auto it = std::find(d.begin(), d.end(), x);
      uint32_t id =
          it == d.end() ? (d.push_back(x), d.size() - 1) : it - d.begin();
      put32(f, id);
      saveDict(t, c.name, d);
    } else if (tg == 3)
      put32(f, std::stol(x));
    else if (tg == 4)
      put64(f, std::stoll(x));
    else if (tg == 2) {
      auto z = std::stoi(x);
      f.put(z >> 8);
      f.put(z);
    } else if (tg == 1 || tg == 7)
      f.put((char)(tg == 7 ? (upper(x) == "TRUE" || x == "1") : std::stoi(x)));
    else if (tg == 5) {
      float z = std::stof(x);
      uint32_t b;
      std::memcpy(&b, &z, 4);
      put32(f, b);
    } else if (tg == 6) {
      double z = std::stod(x);
      uint64_t b;
      std::memcpy(&b, &z, 8);
      put64(f, b);
    }
  }
  void erase(const std::string &t, const Schema &s, int row) {
    for (auto &c : s.columns) {
      auto p = path(t, c.name);
      std::fstream f(p, std::ios::in | std::ios::out | std::ios::binary);
      if (!f)
        continue;
      int tg = get32(f), n = get32(f), live = 0;
      for (int i = 0; i < n; ++i) {
        auto pos = f.tellg();
        int fl = f.get();
        if (fl != 255 && live++ == row) {
          f.seekp(pos);
          f.put((char)255);
          break;
        }
        f.seekg(width(tg), std::ios::cur);
      }
    }
  }
  void drop(const std::string &t) {
    std::error_code ec;
    fs::remove_all(root / "tables" / t, ec);
  }
};

struct Cond {
  std::string col, op, val;
};
struct Where {
  std::vector<std::vector<Cond>> groups;
  std::string expression;
};
inline bool compare(std::string a, std::string op, std::string b) {
  a = unquote(a);
  b = unquote(b);
  if (upper(a) == "TRUE")
    a = "1";
  if (upper(a) == "FALSE")
    a = "0";
  if (upper(b) == "TRUE")
    b = "1";
  if (upper(b) == "FALSE")
    b = "0";
  try {
    double x = std::stod(a), y = std::stod(b);
    if (op == "=")
      return x == y;
    if (op == "!=")
      return x != y;
    if (op == ">")
      return x > y;
    if (op == "<")
      return x < y;
    if (op == ">=")
      return x >= y;
    if (op == "<=")
      return x <= y;
  } catch (...) {
    if (op == "=")
      return upper(a) == upper(b);
    if (op == "!=")
      return upper(a) != upper(b);
  }
  return false;
}
inline Where parseWhere(const std::string &s) {
  Where w;
  w.expression = trim(s);
  return w;
}

inline std::string stripOuterParentheses(std::string s) {
  s = trim(s);
  while (s.size() >= 2 && s.front() == '(' && s.back() == ')') {
    int depth = 0;
    bool wrapsAll = true;
    char quote = 0;
    for (size_t i = 0; i < s.size(); ++i) {
      const char c = s[i];
      if ((c == '\'' || c == '"') && (!quote || quote == c))
        quote = quote ? 0 : c;
      if (quote)
        continue;
      if (c == '(')
        ++depth;
      else if (c == ')' && --depth == 0 && i + 1 < s.size()) {
        wrapsAll = false;
        break;
      }
    }
    if (!wrapsAll)
      break;
    s = trim(s.substr(1, s.size() - 2));
  }
  return s;
}

inline std::vector<std::string> splitLogical(const std::string &s,
                                             const std::string &word) {
  std::vector<std::string> parts;
  int depth = 0;
  char quote = 0;
  size_t start = 0;
  const std::string upperText = upper(s);
  const std::string upperWord = upper(word);
  for (size_t i = 0; i + upperWord.size() <= s.size(); ++i) {
    const char c = s[i];
    if ((c == '\'' || c == '"') && (!quote || quote == c))
      quote = quote ? 0 : c;
    if (quote)
      continue;
    if (c == '(') ++depth;
    if (c == ')') --depth;
    if (depth == 0 && upperText.compare(i, upperWord.size(), upperWord) == 0 &&
        (i == 0 || std::isspace((unsigned char)s[i - 1])) &&
        (i + upperWord.size() == s.size() ||
         std::isspace((unsigned char)s[i + upperWord.size()]))) {
      parts.push_back(trim(s.substr(start, i - start)));
      start = i + upperWord.size();
      i = start - 1;
    }
  }
  parts.push_back(trim(s.substr(start)));
  return parts;
}

template <typename Lookup>
inline bool evaluateWhere(const std::string &raw, Lookup lookup) {
  const std::string expression = stripOuterParentheses(raw);
  const auto orParts = splitLogical(expression, "OR");
  if (orParts.size() > 1) {
    for (const auto &part : orParts)
      if (evaluateWhere(part, lookup)) return true;
    return false;
  }
  const auto andParts = splitLogical(expression, "AND");
  if (andParts.size() > 1) {
    for (const auto &part : andParts)
      if (!evaluateWhere(part, lookup)) return false;
    return true;
  }
  std::smatch m;
  const std::string condition = trim(expression);
  if (!std::regex_match(condition, m,
                        std::regex("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*"
                                   "(>=|<=|!=|=|>|<)\\s*(.*?)\\s*")))
    throw std::runtime_error("Invalid WHERE condition: " + condition);
  return compare(lookup(m[1].str()), m[2].str(), unquote(m[3].str()));
}

class Database {
  SchemaManager sm;
  Storage st;
  std::map<std::string,
           std::map<std::string, std::map<std::string, std::set<int>>>>
      idx;
  void rebuild(const std::string &t) {
    idx[t].clear();
    auto *s = sm.get(t);
    if (!s)
      return;
    for (auto &c : s->columns)
      if (c.categorical()) {
        auto v = st.read(t, c.name);
        std::set<std::string> d(v.begin(), v.end());
        if (d.size() > 100 ||
            (v.size() >= 10 && double(d.size()) / v.size() > .5))
          continue;
        for (int i = 0; i < (int)v.size(); ++i)
          idx[t][c.name][v[i]].insert(i);
      }
  }
  std::vector<int> rows(const std::string &t, const Schema &s, const Where *w) {
    int n = 0;
    for (auto &c : s.columns)
      n = std::max(n, (int)st.read(t, c.name).size());
    std::vector<int> out;
    std::map<std::string, std::vector<std::string>> data;
    for (auto &c : s.columns)
      data[c.name] = st.read(t, c.name);
    for (int i = 0; i < n; ++i) {
      bool ok = !w;
      if (w && !w->expression.empty()) {
        ok = evaluateWhere(w->expression, [&](const std::string &column) {
          auto it = data.find(column);
          return it != data.end() && i < (int)it->second.size()
                     ? it->second[i]
                     : "null";
        });
      } else if (w)
        for (auto &g : w->groups) {
          bool a = true;
          for (auto &x : g) {
            if (!compare(i < (int)data[x.col].size() ? data[x.col][i] : "null",
                         x.op, x.val)) {
              a = false;
              break;
            }
          }
          if (a) {
            ok = true;
            break;
          }
        }
      if (ok)
        out.push_back(i);
    }
    return out;
  }

public:
  explicit Database(const fs::path &r) : sm(r), st(r) {
    for (auto &x : sm.all())
      rebuild(x.first);
  }
  std::string execute(std::string q) {
    try {
      q = trim(q);
      if (!q.empty() && q.back() == ';')
        q.pop_back();
      std::smatch m;
      if (std::regex_match(q, m,
                           rx("CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)"))) {
        Schema s;
        s.name = m[1];
        for (auto &d : split_csv(m[2])) {
          std::istringstream z(d);
          Column c;
          z >> c.name >> c.type;
          std::string x;
          while (z >> x)
            c.constraints.push_back(x);
          s.columns.push_back(c);
        }
        sm.create(s);
        st.create(s);
        rebuild(s.name);
        return "Table " + s.name + " created successfully.";
      }
      if (std::regex_match(q, m, rx("DROP\\s+TABLE\\s+(\\w+)"))) {
        st.drop(m[1]);
        sm.drop(m[1]);
        idx.erase(m[1]);
        return "Table dropped successfully.";
      }
      if (std::regex_match(
              q, m, rx("INSERT\\s+INTO\\s+(\\w+)\\s+VALUES\\s*\\((.*)\\)"))) {
        auto *s = sm.get(m[1].str());
        if (!s)
          throw std::runtime_error("Table not found");
        auto v = split_csv(m[2]);
        if (v.size() != s->columns.size())
          throw std::runtime_error("Column count doesn't match");
        for (size_t i = 0; i < v.size(); ++i) {
          if (s->columns[i].has("NOT_NULL") && upper(unquote(v[i])) == "NULL")
            throw std::runtime_error("NOT_NULL violation");
          st.append(s->name, s->columns[i], v[i]);
        }
        rebuild(s->name);
        return "1 row inserted.";
      }
      if (std::regex_match(
              q, m,
              rx("SELECT\\s+(.*?)\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?"))) {
        auto *s = sm.get(m[2]);
        if (!s)
          throw std::runtime_error("Table not found");
        std::vector<std::string> cols;
        if (trim(m[1]) == "*")
          for (auto &c : s->columns)
            cols.push_back(c.name);
        else
          cols = split_csv(m[1]);
        Where w;
        if (m[3].matched)
          w = parseWhere(m[3]);
        auto rr = rows(s->name, *s, m[3].matched ? &w : nullptr);
        std::vector<std::vector<std::string>> d;
        for (auto &c : cols)
          d.push_back(st.read(s->name, c));
        std::ostringstream o;
        for (size_t j = 0; j < cols.size(); ++j)
          o << (j ? "\t" : "") << cols[j];
        o << "\n";
        for (int i : rr) {
          for (size_t j = 0; j < cols.size(); ++j)
            o << (j ? "\t" : "") << (i < (int)d[j].size() ? d[j][i] : "null");
          o << "\n";
        }
        o << "(" << rr.size() << " rows)";
        return o.str();
      }
      if (std::regex_match(q, m,
                           rx("UPDATE\\s+(\\w+)\\s+SET\\s+(\\w+)\\s*=\\s*(.*?)"
                              "\\s*(?:WHERE\\s+(.+))?"))) {
        auto *s = sm.get(m[1]);
        if (!s || !s->column(m[2]))
          throw std::runtime_error("Table/column not found");
        Where w;
        if (m[4].matched)
          w = parseWhere(m[4]);
        auto rr = rows(s->name, *s, m[4].matched ? &w : nullptr);
        for (int i : rr)
          st.update(s->name, *s->column(m[2]), i, m[3]);
        rebuild(s->name);
        return std::to_string(rr.size()) + " rows updated.";
      }
      if (std::regex_match(
              q, m, rx("DELETE\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?"))) {
        auto *s = sm.get(m[1]);
        if (!s)
          throw std::runtime_error("Table not found");
        Where w;
        if (m[2].matched)
          w = parseWhere(m[2]);
        auto rr = rows(s->name, *s, m[2].matched ? &w : nullptr);
        for (auto i = rr.rbegin(); i != rr.rend(); ++i)
          st.erase(s->name, *s, *i);
        rebuild(s->name);
        return std::to_string(rr.size()) + " rows deleted.";
      }
      throw std::runtime_error("Unsupported query");
    } catch (const std::exception &e) {
      return std::string("Error: ") + e.what();
    }
  }
  std::string dump(const std::string &t) const {
    auto it = idx.find(t);
    if (it == idx.end() || it->second.empty())
      return "No bitmap index found for table '" + t + "'.";
    std::ostringstream o;
    o << "Bitmap Index for table '" << t << "':\n";
    for (auto &c : it->second)
      for (auto &v : c.second) {
        o << "  " << c.first << "='" << v.first << "': ";
        for (auto i : v.second)
          o << i << " ";
        o << "\n";
      }
    return o.str();
  }
  const SchemaManager &schemas() const { return sm; }
};

class Shell {
  fs::path root;
  std::unique_ptr<Database> db;
  std::string name;

public:
  explicit Shell(fs::path r) : root(std::move(r)) {}
  void run(std::istream &in, std::ostream &out) {
    out << "Column-Store DB (C++17). Type HELP for help.\n";
    std::string q;
    while (out << "CDB" << (name.empty() ? "" : (" [" + name + "]")) << " > ",
           std::getline(in, q)) {
      q = trim(q);
      if (q.empty())
        continue;
      if (!q.empty() && q.back() == ';')
        q = trim(q.substr(0, q.size() - 1));
      auto u = upper(q);
      if (u == "EXIT" || u == "QUIT")
        break;
      if (u == "HELP") {
        out << "CREATE DATABASE n | USE DATABASE n | SHOW DATABASES | SHOW "
               "TABLES | SHOW BITMAP INDEX t | SQL CRUD | EXIT\n";
        continue;
      }
      if (u == "SHOW DATABASES") {
        for (auto &e : fs::directory_iterator(root))
          if (e.is_directory())
            out << e.path().filename() << "\n";
        continue;
      }
      if (u.rfind("CREATE DATABASE ", 0) == 0) {
        fs::create_directories(root / trim(q.substr(16)));
        out << "Database created.\n";
        continue;
      }
      if (u.rfind("USE DATABASE ", 0) == 0) {
        name = trim(q.substr(13));
        auto p = root / name;
        if (!fs::exists(p)) {
          out << "Error: database not found.\n";
          name.clear();
          db.reset();
        } else {
          db = std::make_unique<Database>(p);
          out << "Switched to " << name << ".\n";
        }
        continue;
      }
      if (u == "SHOW TABLES") {
        if (db)
          for (auto &x : db->schemas().all())
            out << x.first << "\n";
        else
          out << "No database selected.\n";
        continue;
      }
      if (u.rfind("SHOW BITMAP INDEX ", 0) == 0) {
        if (db)
          out << db->dump(trim(q.substr(18))) << "\n";
        continue;
      }
      if (!db) {
        out << "No database selected.\n";
        continue;
      }
      out << db->execute(q) << "\n";
    }
  }
};
} // namespace cdb
