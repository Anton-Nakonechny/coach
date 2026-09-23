# 02 Multithreading and Concurrency

Thread safety, the java.util.concurrent toolbox, and the memory-model reasoning
that explains why naive concurrent code breaks under load.

- How does ConcurrentHashMap internally handle locking (segment locking versus CAS)?
- When should CopyOnWriteArrayList be used, and what is its memory overhead?
- Explain the difference between ForkJoinPool and a normal ExecutorService in the context of work-stealing.
- How does ThreadLocal create a memory leak, and how do you avoid it?
- Explain the practical difference between thenApply(), thenCompose(), and thenCombine() in CompletableFuture.
- Explain the use case for Semaphore in the context of connection pooling.
- What is the happens-before relationship in the Java Memory Model?
- What is the difference between volatile and synchronized, and when would you reach for each?
- What is the difference between a Process and a Thread?
- What is a deadlock, and how do you identify it in production?
- What is ExecutorService, and how do you use it?
- What is the difference between wait(), sleep(), and join()?
- What is a race condition, and how do you spot one?
- Two threads update the same record simultaneously — how would you prevent inconsistent data?
