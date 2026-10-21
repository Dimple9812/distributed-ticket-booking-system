export default function AboutPage() {
  return (
    <div className="narrow prose">
      <h1>How it works</h1>
      <p>
        This is the demo frontend for a Spring Boot backend whose one hard rule is: <strong>a seat for a screening
        can never be sold twice</strong>, however many people click at the same moment.
      </p>
      <h2>Holding seats</h2>
      <ol>
        <li><strong>Redis</strong> takes a lock per seat (<code>SET NX</code> with a 5-minute expiry). Most conflicting
          requests are rejected here, before they reach the database.</li>
        <li><strong>PostgreSQL</strong> then locks the seat rows in a fixed order and flips them from AVAILABLE to HELD in one
          conditional update. If not every seat flips, nothing is held.</li>
        <li>A unique index guarantees at most one active booking per seat, even if every check above were bypassed.</li>
      </ol>
      <h2>Confirming</h2>
      <p>
        Payment (simulated), the booking, the seat update and an outbox event commit in a single transaction. An
        <code> Idempotency-Key</code> makes double-clicks and retries safe: a repeated request gets the original answer.
      </p>
      <h2>Notifications</h2>
      <p>
        A background publisher sends outbox events to <strong>Kafka</strong> and marks them only after the broker
        acknowledges. The consumer de-duplicates on the event id, so redelivered events never create duplicate
        notifications.
      </p>
      <h2>Try it</h2>
      <ul>
        <li>Open a screening, hold seats, wait out the 5-minute timer and watch the hold expire.</li>
        <li>Pay with the "declined" test card: no booking is created and your hold stays.</li>
        <li>Use <em>Run the race</em> on a seat map: 15 simultaneous holds, exactly one wins.</li>
        <li>Open the same screening in two browsers and try to grab the same seat.</li>
      </ul>
    </div>
  );
}
