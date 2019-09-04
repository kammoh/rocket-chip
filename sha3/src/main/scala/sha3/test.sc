def lfsrStream(lfsr: Byte): Stream[Byte] =
  Stream.cons(lfsr, lfsrStream(((lfsr << 1) ^ (if ((lfsr & 0x80) == 0) 0 else 0x71)).toByte))

for (x <- lfsrStream(1).map(_ & 1).grouped(7).map(_.zipWithIndex.map({case (d, i) => BigInt(d) << ((1 << i) - 1) }).sum).take(25)){
  println(x.toString(16))
}