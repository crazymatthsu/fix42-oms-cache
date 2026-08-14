package com.fix42.oms.examples;

import com.fix42.oms.api.OmsCache;
import com.fix42.oms.proto.OrderState;

/**
 * End-to-end demo: feed a realistic FIX 4.2 order lifecycle (and a parent/child basket)
 * into the cache and print the resulting latest state. Messages are written with '|' in
 * place of SOH for readability; the cache auto-detects the delimiter.
 *
 * <p>Run: {@code ./gradlew :examples:run}
 */
public final class OrderStreamDemo {

    public static void main(String[] args) {
        OmsCache cache = OmsCache.inMemory();

        System.out.println("=== Single order lifecycle: new -> ack -> partial fill -> replace -> fill ===");

        // 1) NewOrderSingle: buy 1,000 IBM @ 185.50, ClOrdID=ORD1001
        cache.process("8=FIX.4.2|35=D|49=BUY|56=SELL|34=1|52=20260813-13:45:07|"
                + "11=ORD1001|1=ACC1|21=1|55=IBM|54=1|38=1000|40=2|44=185.50|59=0|60=20260813-13:45:07|");

        // 2) ExecutionReport PendingNew, then New (OrderID assigned by sell-side)
        cache.process("8=FIX.4.2|35=8|49=SELL|56=BUY|34=1|11=ORD1001|37=EX9001|17=E1|"
                + "20=0|150=A|39=A|1=ACC1|55=IBM|54=1|38=1000|44=185.50|151=1000|14=0|6=0|");
        cache.process("8=FIX.4.2|35=8|49=SELL|56=BUY|34=2|11=ORD1001|37=EX9001|17=E2|"
                + "20=0|150=0|39=0|1=ACC1|55=IBM|54=1|38=1000|44=185.50|151=1000|14=0|6=0|");

        // 3) Partial fill: 400 @ 185.50
        cache.process("8=FIX.4.2|35=8|49=SELL|56=BUY|34=3|11=ORD1001|37=EX9001|17=E3|"
                + "20=0|150=1|39=1|1=ACC1|55=IBM|54=1|38=1000|32=400|31=185.50|30=XNYS|151=600|14=400|6=185.50|");

        print(cache, "EX9001", "after partial fill");

        // 4) Cancel/Replace: raise qty to 1,200, new ClOrdID=ORD1002 referencing ORD1001
        cache.process("8=FIX.4.2|35=G|49=BUY|56=SELL|34=2|11=ORD1002|41=ORD1001|37=EX9001|"
                + "1=ACC1|21=1|55=IBM|54=1|38=1200|40=2|44=185.50|59=0|60=20260813-13:46:00|");
        print(cache, "EX9001", "after replace request (PENDING_REPLACE, terms unchanged)");

        // 5) Replace confirmed: ExecType=Replaced, OrdStatus back to PartiallyFilled, qty now 1,200
        cache.process("8=FIX.4.2|35=8|49=SELL|56=BUY|34=4|11=ORD1002|41=ORD1001|37=EX9001|17=E4|"
                + "20=0|150=5|39=1|1=ACC1|55=IBM|54=1|38=1200|44=185.50|32=0|151=800|14=400|6=185.50|");
        print(cache, "EX9001", "after replace confirmed");

        // 6) Final fill: remaining 800 @ 185.55 -> Filled
        cache.process("8=FIX.4.2|35=8|49=SELL|56=BUY|34=5|11=ORD1002|37=EX9001|17=E5|"
                + "20=0|150=2|39=2|1=ACC1|55=IBM|54=1|38=1200|32=800|31=185.55|30=XNYS|151=0|14=1200|6=185.5333|");
        print(cache, "EX9001", "after final fill");

        System.out.println("\nLook up the same order by its original ClOrdID (ORD1001): "
                + cache.getByClOrdId("ORD1001").map(OrderState::getOrderId).orElse("<none>"));

        System.out.println("\n=== Parent / child basket (children link to parent via tag 526) ===");
        OmsCache basket = OmsCache.inMemory();
        // Parent order PAR1
        basket.process("8=FIX.4.2|35=D|49=BUY|56=SELL|11=PAR1|1=DESK|55=AAPL|54=1|38=500|40=1|60=20260813-14:00:00|");
        basket.process("8=FIX.4.2|35=8|11=PAR1|37=PARENT|17=P1|20=0|150=0|39=0|1=DESK|55=AAPL|54=1|38=500|151=500|14=0|");
        // Two child slices reference the parent (526=PARENT), each fully filled
        basket.process("8=FIX.4.2|35=D|49=BUY|56=SELL|11=CH1|526=PARENT|1=DESK|55=AAPL|54=1|38=300|40=1|60=20260813-14:00:01|");
        basket.process("8=FIX.4.2|35=8|11=CH1|526=PARENT|37=CHILD1|17=C1|20=0|150=2|39=2|1=DESK|55=AAPL|54=1|38=300|32=300|31=190.10|151=0|14=300|6=190.10|");
        basket.process("8=FIX.4.2|35=D|49=BUY|56=SELL|11=CH2|526=PARENT|1=DESK|55=AAPL|54=1|38=200|40=1|60=20260813-14:00:02|");
        basket.process("8=FIX.4.2|35=8|11=CH2|526=PARENT|37=CHILD2|17=C2|20=0|150=2|39=2|1=DESK|55=AAPL|54=1|38=200|32=200|31=190.20|151=0|14=200|6=190.20|");

        OrderState parent = basket.getByOrderId("PARENT").orElseThrow();
        System.out.printf("Parent PARENT: isParent=%b children=%s aggregatedCumQty=%.0f aggregatedAvgPx=%.4f%n",
                parent.getIsParent(), parent.getChildOrderIdsList(), parent.getCumQty(), parent.getAvgPx());
        System.out.println("Children of PARENT: " + basket.getChildren("PARENT").stream()
                .map(OrderState::getOrderId).toList());
        System.out.println("Parent of CHILD1: "
                + basket.getParent("CHILD1").map(OrderState::getOrderId).orElse("<none>"));
    }

    private static void print(OmsCache cache, String orderId, String label) {
        OrderState s = cache.getByOrderId(orderId).orElseThrow();
        System.out.printf("%-42s status=%-16s cum=%.0f leaves=%.0f avg=%.4f qty=%.0f clOrdId=%s%n",
                label,
                s.getOrdStatus().name().replace("ORD_STATUS_", ""),
                s.getCumQty(), s.getLeavesQty(), s.getAvgPx(), s.getOrderQty(), s.getClOrdId());
    }

    private OrderStreamDemo() {
    }
}
