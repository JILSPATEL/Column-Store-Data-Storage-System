# Bitmap Index Data Representation

## 1. Raw Data Table (`Orders`)

| Row | Status | Region | Priority |
| :--- | :--- | :--- | :--- |
| **0** | Pending | North | High |
| **1** | Shipped | South | Medium |
| **2** | Pending | East | Low |
| **3** | Cancelled | North | Low |
| **4** | Shipped | West | High |
| **5** | Pending | South | Medium |
| **6** | Shipped | North | High |
| **7** | Pending | East | Medium |
| **8** | Cancelled | West | Low |
| **9** | Shipped | South | High |
| **10** | Pending | North | Low |
| **11** | Shipped | East | Medium |

---

## 2. Column 1: Status

**Distinct values:**
`Pending`, `Shipped`, `Cancelled`

**Bitmaps:**
- `Pending`   → `101001010010`
- `Shipped`   → `010010100101`
- `Cancelled` → `000100001000`

---

## 3. Column 2: Region

**Distinct values:**
`North`, `South`, `East`, `West`

**Bitmaps:**
- `North` → `100100100010`
- `South` → `010001000100`
- `East`  → `001000010001`
- `West`  → `000010001000`

---

## 4. Column 3: Priority

**Distinct values:**
`High`, `Medium`, `Low`

**Bitmaps:**
- `High`   → `100010100100`
- `Medium` → `010001010001`
- `Low`    → `001100001010`
