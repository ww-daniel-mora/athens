# Athens Backend Architecture

## 🌐 Athens Backend: Complete Breakdown

### 📍 Main Entry Point

**File:** `/src/clj/athens/self_hosted/core.clj`

**The `-main` function (line 33-37):**
```clojure
(defn -main
  [& _args]
  (log/info "Athens Self-Hosted Starting")
  (alter-var-root #'system component/start)
  (log/info "Athens Self-Hosted ready to do thy bidding"))
```

**How to run it:**
```bash
# Development
npm run server              # clojure -M:athens

# Production (uberjar)
npm run server:uberjar      # Creates standalone JAR
java -jar target/athens-lan-party-standalone.jar

# Docker
docker-compose up athens
```

---

## 🎯 What the Backend is Responsible For

### Primary Purpose: Multi-User Collaboration Server

The Athens backend enables **multiple people to work on the same knowledge graph simultaneously** with real-time synchronization.

---

## 🏗️ System Components

The backend uses **Stuart Sierra's Component** architecture with 5 main components:

### 1. ⚙️ Config Component
```clojure
:config (cfg/new-config)
```

**Responsibilities:**
- Load configuration from `config.edn`
- Environment variables
- Default settings:
  ```edn
  {:http       {:port 3010}
   :fluree     {:servers ["http://fluree:8090"]}
   :datascript {:persist-base-path "/srv/athens/datascript/persist/"}
   :password   "SuchWow"  ; Optional auth
   :nrepl      {:port 8877}}
  ```

---

### 2. 🗄️ Fluree Component
```clojure
:fluree (component/using (fluree/new-fluree) [:config])
```

**File:** `/src/clj/athens/self_hosted/components/fluree.clj`

**Responsibilities:**
- **Event Log Storage** - Append-only blockchain ledger
- **Event Sourcing** - All user actions stored as immutable events
- **Distributed Database** - Can run across multiple nodes
- **Audit Trail** - Complete history of all changes
- **Conflict Resolution** - Manages concurrent edits

**Why Fluree?**
- Immutable event log (perfect for collaboration)
- Time-travel queries (see data at any point in time)
- Built-in security and permissions
- Blockchain-based for integrity

**Connection:**
- Connects to Fluree on port 8090
- Creates/initializes event log
- Can run in-memory mode (`:in-memory? true`)

---

### 3. 💾 Datascript Component
```clojure
:datascript (component/using (datascript/new-datascript)
                             [:config :fluree])
```

**File:** `/src/clj/athens/self_hosted/components/datascript.clj`

**Responsibilities:**
- **In-Memory Database** - Fast queries on current state
- **Datalog Queries** - Powerful graph queries
- **Materialized View** - Built from Fluree event log
- **State Reconstruction** - Replays events to rebuild current state
- **Persistence** - Snapshots saved to disk

**How it works:**
```
Fluree (Event Log)          Datascript (Current State)
─────────────────           ──────────────────────────
Event 1: Create page   →    Pages: {...}
Event 2: Add block     →    Blocks: {...}
Event 3: Link pages    →    Links: {...}
Event 4: Edit block    →    Updated state
     ...                    (materialized view)
```

---

### 4. 🌐 Web Server Component
```clojure
:webserver (component/using (web/new-web-server)
                            [:config :datascript :fluree])
```

**File:** `/src/clj/athens/self_hosted/components/web.clj`

**Responsibilities:**

#### A. WebSocket Server (`/ws`)
Real-time bidirectional communication:

**Client → Server:**
- `:presence/hello` - Client connects, introduces itself
- `:op/atomic` - Graph operations (create/edit/delete blocks)
- `:presence/goodbye` - Client disconnects

**Server → Clients:**
- Event acknowledgments (`:accepted` or `:rejected`)
- Broadcast updates to all connected clients
- Presence updates (who's online)

**Event Flow:**
```
Client 1                 Server                  Client 2
   │                        │                        │
   ├──► :op/atomic ────────►│                        │
   │    (edit block)        │                        │
   │                        ├─► Validate event       │
   │                        ├─► Apply to Datascript  │
   │                        ├─► Save to Fluree      │
   │                        │                        │
   │◄──── :accepted ────────┤                        │
   │                        │                        │
   │                        ├──► Broadcast ──────────►│
   │                        │    (all clients see it)│
```

#### B. HTTP API Routes
```clojure
GET  /              → Web client (index.html)
GET  /health-check  → Server health status
GET  /ws            → WebSocket upgrade
POST /api/*         → RESTful API endpoints
```

**API Endpoints:**
- Page queries
- Block operations
- Path resolution
- Data import/export

#### C. Static File Serving
Serves the Athens web client (compiled ClojureScript)

**Technology:**
- **http-kit** - High-performance async web server
- **Compojure** - Routing
- **Ring** - Middleware (auth, resources, etc.)

---

### 5. 🔌 nREPL Component
```clojure
:nrepl (component/using (nrepl/new-nrepl-server) [:config])
```

**File:** `/src/clj/athens/self_hosted/components/nrepl.clj`

**Responsibilities:**
- **Networked REPL** - Remote debugging
- **Live Coding** - Update running server without restart
- **Debugging** - Inspect state, run queries
- **Development** - Test functions interactively

**Default Port:** 8777

---

## 🔄 How It All Works Together

### Complete Data Flow:

```
┌─────────────────────────────────────────────────────┐
│                   Athens Client                     │
│            (Browser/Electron - JavaScript)          │
└────────────────────┬────────────────────────────────┘
                     │ WebSocket
                     ↓
┌─────────────────────────────────────────────────────┐
│              Web Server (http-kit)                  │
│                  Port 3010                          │
├─────────────────────────────────────────────────────┤
│  • WebSocket Handler (/ws)                         │
│    - Receive events from clients                   │
│    - Validate events                               │
│    - Broadcast to all clients                      │
│                                                     │
│  • HTTP API Routes                                 │
│    - Health check                                  │
│    - RESTful operations                            │
│    - Static file serving                           │
└────────┬───────────────────────────┬────────────────┘
         │                           │
         ↓                           ↓
┌─────────────────────┐    ┌─────────────────────────┐
│    Datascript       │    │       Fluree DB         │
│   (In-Memory DB)    │◄───┤   (Event Log/Ledger)    │
│   Port: N/A         │    │   Port: 8090            │
├─────────────────────┤    ├─────────────────────────┤
│ • Current state     │    │ • Immutable event log   │
│ • Fast queries      │    │ • Blockchain ledger     │
│ • Materialized view │    │ • Audit trail           │
│ • Disk snapshots    │    │ • Time-travel queries   │
└─────────────────────┘    └─────────────────────────┘
         ↓                           ↓
┌─────────────────────┐    ┌─────────────────────────┐
│   Disk Storage      │    │   Disk Storage          │
│ /datascript/persist │    │   /fluree/              │
└─────────────────────┘    └─────────────────────────┘
```

---

## 📝 Typical Operation Sequence

### User Edits a Block:

1. **Client** sends event via WebSocket:
   ```clojure
   {:event/id       "uuid-123"
    :event/type     :op/atomic
    :op/atomic      {:block/save {:uid "block-456"
                                  :string "New content"}}}
   ```

2. **Web Server** receives and validates:
   - Is event schema valid?
   - Is user authenticated?
   - Does event make sense?

3. **Datascript Handler** processes operation:
   - Apply to in-memory database
   - Generate transaction data

4. **Fluree** persists event:
   - Append to immutable log
   - Permanently stored

5. **Server** broadcasts to all clients:
   ```clojure
   {:event/id     "uuid-123"
    :event/status :accepted
    ...}
   ```

6. **All Clients** receive update:
   - Update their local Datascript DB
   - Re-render UI with new data

---

## 🎭 Use Cases

### When You Need the Backend:

1. **Team Collaboration**
   - Multiple people editing same graph
   - Real-time sync
   - Conflict resolution

2. **Self-Hosted Knowledge Base**
   - Own your data
   - Run on your infrastructure
   - Custom authentication

3. **LAN Party Mode**
   - Local network collaboration
   - No internet required

4. **Audit & Compliance**
   - Complete change history
   - Who changed what, when
   - Immutable log

### When You DON'T Need It:

1. **Personal Use**
   - Single user
   - Electron app with local storage
   - No collaboration needed

2. **Web-Only Mode**
   - Using Athens hosted version
   - Cloud backend managed by Athens team

---

## 🔐 Security Features

```clojure
:password "SuchWow"  ; Optional password protection
```

- Basic authentication on API routes
- WebSocket connection validation
- User presence tracking
- Event validation and schema checking

---

## 🚀 Deployment Options

### 1. Docker Compose (Recommended)
```bash
docker-compose up
```
- Athens backend + Fluree + nginx
- All configured and networked
- Persistent volumes for data

### 2. Standalone Uberjar
```bash
java -jar athens-lan-party-standalone.jar
```
- Single JAR file
- Requires separate Fluree instance
- Portable deployment

### 3. Development Mode
```bash
npm run server
```
- Live reloading
- nREPL for debugging
- Hot code updates

---

## Summary

**The Athens Backend Main Entry Point:**
- **File:** `/src/clj/athens/self_hosted/core.clj`
- **Function:** `-main`

**What It Does:**
- Enables **real-time multi-user collaboration**
- Manages **WebSocket connections** for live sync
- Uses **Fluree** for immutable event log
- Uses **Datascript** for fast in-memory queries
- Provides **HTTP API** for operations
- Includes **nREPL** for debugging

**It's Optional:** Athens can run without the backend in single-user Electron mode.

**When You Need It:** Team collaboration, self-hosting, or audit trails.
