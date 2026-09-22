# 07 JVM Internals

Memory layout and garbage collection mechanics — the internals that explain
why a Java service behaves the way it does under memory pressure.

- How is memory allocated for static, final, and static final variables in the JVM?
- Explain how the G1 Garbage Collector works, and why it's often preferred over CMS for high-heap applications.
- How do you detect a memory leak in the JVM, using heap-dump analysis tools?
- Explain garbage collection in the JVM at a general level — what makes an object eligible for collection?
