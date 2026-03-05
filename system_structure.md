ColumnDB/
│
├── client/
│   └── CLIClient.java
│
├── parser/
│   ├── SQLParser.java
│   ├── DDLParser.java
│   └── DMLParser.java
│
├── schema/
│   ├── SchemaManager.java
│   └── TableSchema.java
│
├── query/
│   ├── QueryProcessor.java
│   └── CRUDHandler.java
│
├── api/
│   └── DBEngine.java
│
├── jni/
│   └── NativeBridge.java
│
├── metadata/
│   └── MetadataManager.java
│
├── storage_cpp/
│   ├── ColumnStorage.cpp
│   ├── ColumnStorage.h
│   ├── ExecutionEngine.cpp
│   └── ExecutionEngine.h
│
├── data/
│   └── tables/
│
└── main/
    └── Main.java