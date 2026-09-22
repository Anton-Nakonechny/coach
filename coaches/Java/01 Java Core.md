# 01 Java Core

Language fundamentals, the collections most candidates reach for daily, and the
object-oriented design judgment that separates working code from good design.

- How does intern() work with the String pool, and what is the memory-level difference between new String("abc") and the literal "abc"?
- How would you implement immutability in a Java class, and why is String itself immutable — explain the internal reasoning.
- How does the diamond problem get resolved in Java through default methods in interfaces?
- Why aren't multiple abstract methods allowed in a functional interface?
- What performance issues can autoboxing and unboxing cause in production code?
- How does the var keyword (Java 10+) resolve type inference internally at compile time?
- What is the correct use case for the Optional class, and how is it commonly misused?
- How does the Reflection API work in Java, and what are its performance trade-offs?
- How does exception chaining (the cause field) help with debugging in production?
- How do the JVM, the JRE, and the JDK differ from each other?
- What is the difference between == and .equals(), and when does each one matter?
- What is the difference between ArrayList and LinkedList, and how would you choose between them?
- What is the difference between Comparable and Comparator?
- Explain Java 8 Streams and lambda expressions, and when you would reach for them.
- How does HashMap work internally, how did its internal structure change after Java 8 (the treeify threshold and red-black trees), and why is a plain HashMap not thread-safe compared to ConcurrentHashMap?
- What is the performance impact during HashMap resize and rehashing, and how can it be avoided?
- What is the use case for WeakHashMap versus IdentityHashMap?
- Give a real production example where the Liskov Substitution Principle was violated.
- Describe a scenario where using inheritance instead of composition broke the design.
