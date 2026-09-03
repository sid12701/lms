# Business decisions we need — Bhawana lending platform

**Date:** 1 August 2026
**Who this is for:** Business, finance and compliance. No technical knowledge needed.
**Why you are reading this:** The engineering team has finished reviewing the platform. Most of
the work can start without you. **A small number of things cannot be built until the business
decides what the rule should be.** Those decisions are below.

---

## How to use this document

There are **13 questions**. Each one is written the same way:

- **Issue** — what we are asking you, in one or two lines.
- **What happens today** — how the system behaves right now.
- **Why it matters** — the real-world effect, usually with money attached.
- **Your options** — the choices, with what each one means for the customer and for us.
- **Our suggestion** — what we would pick, and why.
- **Your answer** — a blank line for you to fill in.

You do not need to answer all 13 at once. **Questions 1 to 5 are the ones that block the most
work.** If you only have time for a few, do those first.

Please write your answer directly into this file, or reply with the question numbers and your
choices.

---

## Summary — what each answer unlocks

| # | Question | Who should decide | Urgency |
|---|---|---|---|
| 1 | What do we charge when a customer misses an instalment? | Business + Compliance | **High** — blocks the most work |
| 2 | What do we charge when a customer's bank payment bounces? | Business | **High** |
| 3 | How do we calculate the amount when a customer closes a loan early? | Business + Finance | **High** |
| 4 | Do we charge a fee for closing a loan early? | Business + Compliance | **High** |
| 5 | Can a customer pay an amount that is not the exact instalment? | Business + Operations | **High** |
| 6 | What happens if a customer pays more than they owe? | Business + Finance | Medium |
| 7 | Can a customer choose which instalment their payment goes against? | Operations | Medium |
| 8 | Where should the odd paisa go in a repayment schedule? | Finance | Medium |
| 9 | What is the largest amount we will pay out in one go, and in one day? | Business + Risk | Medium |
| 10 | Is this platform our official book of accounts? | Finance | Medium — changes cost a lot |
| 11 | Which actions should need two people to approve? | Business + Risk | Medium |
| 12 | How do we check that a customer's bank account really belongs to them? | Business | On hold — see note |
| 13 | For the government identity registry, do we build it or buy it? | Business + Compliance | Medium |

---

# Question 1 — What do we charge when a customer misses an instalment?

## Issue

**A customer misses their monthly payment. What do we charge them for being late, and how much?**

## What happens today

Nothing. The system counts how many days late they are, and that is all. **No charge is ever
raised.** A customer who pays 90 days late pays exactly the same as a customer who pays on time.

## Why it matters

Three reasons:

1. **We lose money.** Late payments cost us, and we recover nothing.
2. **There is no consequence.** A late fee is one of the main reasons customers pay on time.
3. **It blocks other work.** We cannot produce the customer's official loan disclosure document
   until we know every charge that can apply. That document is a legal requirement and we are
   already past the deadline for it.

There is also a rule from the Reserve Bank we must follow: a late fee must be a **separate
charge**. It cannot be added on top of the interest, and we cannot charge interest on it.

## Your options

| Option | What the customer sees | What it means for us |
|---|---|---|
| **A. A fixed amount each time** — for example ₹500 plus tax for every missed instalment | Simple. Easy to explain and easy to put in the loan document. | Easiest to build and to reconcile. Most Indian personal-loan lenders do this. |
| **B. A percentage of the overdue amount** — for example 2% per month on what is unpaid | Scales with the size of the loan. Larger loans mean larger fees. | Harder to word correctly so that it stays a fee and does not become interest. |
| **C. Both, with an upper limit** — a fixed amount, plus a percentage, capped | Most complete. Also the hardest to explain. | The most work to build and to disclose. |
| **D. No late fee at all** | Nothing changes for the customer. | A valid answer. It costs us money but removes a lot of complexity. Tell us and we will stop asking. |

## Our suggestion

**Option A — a fixed amount plus tax.** It is simple, it is what customers expect, and it is the
easiest to disclose correctly.

We would also need the amount. For example: ₹500 plus 18% tax per missed instalment.

## Your answer

**Option: ________   Amount: ________**

---

# Question 2 — What do we charge when a customer's bank payment bounces?

## Issue

**We try to collect the monthly payment from the customer's bank account and it fails because
there is no money in it. The bank charges us for that failed attempt. Do we recover that cost
from the customer, and how much?**

## What happens today

Nothing. The system cannot even record that the payment failed. This is a separate problem the
engineering team is already fixing. But we still need to know whether a fee applies.

## Why it matters

Every failed collection attempt costs us a bank charge. If we do not recover it, we absorb it on
every bounce, every month.

## Your options

| Option | What the customer sees | What it means for us |
|---|---|---|
| **A. A fixed bounce charge plus tax** | A charge appears when their payment fails. It must be written in the loan document upfront. | We recover the cost. Standard practice. |
| **B. We absorb it** | Nothing. | We pay the bank charge every time, on every failure. |

## Our suggestion

**Option A.** It is a real cost and recovering it is normal. We need the amount — commonly ₹300
to ₹500 plus tax.

## Your answer

**Option: ________   Amount: ________**

---

# Question 3 — How do we calculate the amount when a customer closes a loan early?

## Issue

**A customer wants to repay their whole loan before the end of the term. How much do we ask them
to pay?**

## What happens today

**We overcharge them, and by a lot.**

Take a real example. A customer borrows **₹5,00,000 for 24 months at 18%**. After paying two
instalments, they want to close the loan.

- What they should pay: **about ₹4,87,200**
- What our system asks for today: **about ₹5,71,900**

That is roughly **₹84,700 too much**, on every early closure.

The reason is that the system charges them all the interest for the full 24 months, even though
they are only borrowing the money for 2 months. They are being charged for 22 months of borrowing
that never happens.

## Why it matters

- It is unfair to the customer, and they will notice.
- It is very likely to attract a regulatory problem if anyone examines it.
- Nobody has been charged this yet, because the feature is not live. **We can fix it before
  anyone is affected.**

## Your options

| Option | What the customer pays | Notes |
|---|---|---|
| **A. Interest only up to the day they settle** | The money they still owe, plus interest for the days they actually used it. | The normal market practice. This is what a customer expects. |
| **B. Interest up to the end of the current month or instalment period** | Slightly more than Option A — we round up to the end of the current period. | Also acceptable. It must be written in the loan document. |
| **C. Interest up to the day they settle, plus a fixed number of extra days** | More than Option A. Used when a lender needs notice for its own funding. | Must be disclosed as a fee, not as interest. |

## Our suggestion

**Option A — interest only up to the day they settle.** It is the standard, it is fair, and it is
the easiest to defend.

## Your answer

**Option: ________**

---

# Question 4 — Do we charge a fee for closing a loan early?

## Issue

**Separate from the interest calculation in Question 3: do we charge the customer a fee for
choosing to close early?**

## What happens today

We do not have a fee. Instead, the extra interest described in Question 3 is acting like a hidden
fee. That is the worst of both worlds — the customer pays extra and it is not disclosed anywhere.

## Why it matters

The Reserve Bank issued a rule that came into force on **1 January 2026**. For our type of loan a
fee is allowed, but:

- It must be **stated upfront** in the customer's loan document.
- We must show **how it is calculated**.
- If we did not disclose it, we cannot charge it.

## Your options

| Option | What the customer sees | Notes |
|---|---|---|
| **A. No fee** | They can close early at any time at no extra cost. | Cleanest position. Removes a regulatory risk. It is also a genuine selling point for our partners. |
| **B. A percentage of the outstanding amount** — for example 2% | A fee appears on their closing statement. | Allowed for our loan type. Must be in the loan document from day one. |
| **C. No fee after an initial period** — for example a fee in the first 6 months, none after | A fee only if they close very early. | A middle position. Common in the market. |

## Our suggestion

**Option A — no fee.** The revenue is small, the regulatory risk is real, and it is something our
partners can advertise.

## Your answer

**Option: ________   If a fee, how much: ________**

---

# Question 5 — Can a customer pay an amount that is not the exact instalment?

## Issue

**If the monthly instalment is ₹24,962.37 and the customer transfers ₹24,962.00, should we accept
it?**

## What happens today

**No. The system rejects it.** It only accepts an amount that matches the instalment to the paisa.

It also rejects:

- Anything less than the full instalment, even ₹1 less.
- Anything more than the full instalment.
- One transfer that covers two instalments.

## Why it matters

Automatic bank collections always send the exact amount, so this works fine for them. The problem
is everything else:

- A customer who transfers money manually will almost never send the exact paisa.
- A customer who wants to pay two months together cannot.
- A customer who is short by ₹50 cannot pay anything at all.

In every one of those cases, **our operations team cannot record the payment**, even though the
money has arrived in our account.

## Your options

| Option | What the customer can do | Notes |
|---|---|---|
| **A. Keep the exact-amount rule for now, and relax it once the payment engine is rebuilt** | Exact amounts only for now. Any amount later. | The rebuild is happening anyway. This is roughly 4 weeks away. |
| **B. Keep the exact-amount rule permanently** | Exact amounts only, for ever. | Every other payment has to be handled outside the system by hand. Our records stop being complete. |
| **C. Accept any amount now** | Any amount immediately. | We do not recommend rushing this. The payment engine has a known problem that a change now would make worse. |

## Our suggestion

**Option A.** Keep it for now, relax it when the payment engine is rebuilt.

## Your answer

**Option: ________**

---

# Question 6 — What happens if a customer pays more than they owe?

## Issue

**A customer's instalment is ₹24,962 and they transfer ₹30,000. What do we do with the extra
₹5,038?**

## What happens today

The extra money is recorded and then **forgotten**. No report shows it, no alert raises it, and
nobody ever looks at it again. The money belongs to the customer and we are not tracking it.

## Your options

| Option | What the customer experiences | Notes |
|---|---|---|
| **A. Hold it as an advance against their next instalment** | Their next payment is reduced by the extra amount. | What most customers expect. Standard in the industry. |
| **B. Reject the payment if it is more than they owe** | Their transfer fails and they must send the exact amount. | Simple and safe. It pushes the problem onto our operations team, who must return the money by hand. |
| **C. Refund it automatically** | The extra amount comes back to them. | Correct in principle. It is the most work, and it needs an approval step so we are not paying money out automatically. |

## Our suggestion

**Option A — hold it as an advance.** It matches what customers expect and it is the least work.

## Your answer

**Option: ________**

---

# Question 7 — Can a customer choose which instalment their payment goes against?

## Issue

**A customer owes instalments 1, 2 and 3. They pay one instalment's worth of money. Does it go
against instalment 1, or can they choose to put it against instalment 3?**

## What happens today

**They can choose, and this causes a real problem.** Our operations team can record a payment
against instalment 3 while 1 and 2 are still unpaid.

Later, if that customer closes their loan early, the system quietly recalculates everything and
moves that payment to instalment 1. **The receipt we already gave the customer now says something
different from our records.** If the customer questions it, we have no good answer.

## Why it matters

This is happening in the data now. It only becomes visible when a customer closes a loan early,
and that feature is currently switched off — so nobody has been affected yet.

## Your options

| Option | What it means | Notes |
|---|---|---|
| **A. Oldest unpaid instalment always comes first** | A payment always clears the oldest debt first. The customer cannot choose. | This is how nearly every lender works. It removes the problem completely. |
| **B. Allow the customer or our team to choose** | More flexibility for unusual cases. | Significantly more work, and our records would not match how the rest of the industry does it. |

## Our suggestion

**Option A — oldest first.** It is what customers expect and it removes the problem at the source.

## Your answer

**Option: ________**

---

# Question 8 — Where should the odd paisa go in a repayment schedule?

## Issue

**When we divide a loan into equal monthly instalments, the numbers never divide perfectly. A few
paisa are always left over. Which instalment absorbs them?**

## What happens today

The leftover amount lands in the **last instalment**, but by accident rather than by decision, and
the amount is larger than it should be because of how we round at each step. The engineering team
is fixing the rounding. We need to know where the leftover should sit.

## Your options

| Option | What the customer sees |
|---|---|
| **A. The last instalment absorbs it** | Every instalment is identical except the final one, which differs by a few rupees. |
| **B. Spread across all instalments** | Every instalment differs by a paisa or two. |
| **C. The first instalment absorbs it** | The first payment is slightly larger than the rest. |

## Our suggestion

**Option A — the last instalment.** Customers and automatic bank collections both prefer a fixed
monthly amount. Changing the first payment is the most noticeable and the worst option.

## Your answer

**Option: ________**

---

# Question 9 — What is the largest amount we will pay out in one go, and in one day?

## Issue

**Right now there is no upper limit on how much money the system will pay out. We need three
numbers.**

## What happens today

The system checks that the paperwork is complete and that the amount is greater than zero. **There
is no maximum.** If something goes wrong — a mistake in a partner's data, a wrong number typed in,
or someone getting access they should not have — there is nothing to stop it.

## Why it matters

This is a safety net. It is not about any specific risk we have identified. It is the financial
equivalent of a circuit breaker.

## What we need from you

Three numbers:

1. **The largest single loan we will ever pay out.** _______________
2. **The largest total we will pay out to one partner in one day.** _______________
3. **The largest total we will pay out across the whole business in one day.** _______________

Set these comfortably above your business plan. If a limit is reached, the payment stops and an
alert is raised. Nothing is lost — a manager can review and release it.

## Our suggestion

Pick numbers roughly two to three times your expected peak. The purpose is to catch something
badly wrong, not to manage day-to-day business.

## Your answer

**Single loan: ________   Per partner per day: ________   Whole business per day: ________**

---

# Question 10 — Is this platform our official book of accounts?

## Issue

**Does our finance team treat this platform as the official accounting record for the loan
business, or do they keep the official accounts in a separate accounting system?**

## Why we are asking

This single answer changes the cost of the work significantly.

- **If the official accounts live somewhere else** and this platform simply feeds numbers into it:
  we do not need to build accounting entries here. We build a clean handover of the numbers
  instead. This is the cheaper path and it is completely normal.
- **If this platform is the official record**: we must build proper double-entry accounting inside
  it. Without that, income from interest is never formally recorded anywhere. This adds roughly
  **six weeks** of work.

## Your options

| Option | What it means |
|---|---|
| **A. The official accounts are kept elsewhere** | This platform reports into that system. Cheaper. |
| **B. This platform is the official record** | We build full accounting inside it. About six weeks more. |

## Our suggestion

We have no view. **This is a finance question, not an engineering one.** We just need the answer
before we plan the work.

## Your answer

**Option: ________**

---

# Question 11 — Which actions should need two people to approve?

## Issue

**Today, one administrator can do anything on their own. Should some actions require a second
person to approve them?**

## What happens today

A single administrator can, with no second approval:

- Create another administrator with full access.
- Change where a customer's money is sent.
- Change the interest rate on a product.
- Release a payment to a customer.
- Override a loan's status.

You have already told us this is acceptable for now, so **no action is needed**. This question is
here so the decision is recorded, and so you can revisit it.

## Why it may matter later

As the team grows, or if we are asked about our internal controls, "one person can do everything"
becomes hard to defend. It is also the standard answer to an auditor's question about separation
of duties.

## Your options

| Option | What it means |
|---|---|
| **A. Leave it as it is for now** | No change. Your current position. |
| **B. Require a second approval for a short list of actions** | Pick from the list above. Roughly two weeks of work. |

## Our suggestion

Revisit this before the team grows or before the first external audit. No action needed today.

## Your answer

**Option: ________   If B, which actions: ________**

---

# Question 12 — How do we check that a customer's bank account really belongs to them?

> **Note: this question is on hold.** It is connected to a banking relationship that is not
> finalised. It is written here so it is ready when you are. **No answer needed right now.**

## Issue

**Before we send money to a customer's bank account, how do we confirm that the account really
belongs to them?**

## What happens today

We do not check. We accept whatever bank account details our partner sends us.

There is a specific weakness in this. Suppose Partner A signs up a customer. Later, Partner B
submits an application for **the same person**, using **a different bank account**. Our system
quietly replaces the original account details with the new ones. If Partner A's loan is then paid
out, **the money goes to the account Partner B supplied**, and nothing in our records shows that
the account was ever changed.

Nobody has done this. It is possible, and it should not be.

## Your options

| Option | What it involves | Time |
|---|---|---|
| **A. Stop partners overwriting each other's details** | Our software refuses the change and raises it for a person to review. No outside service needed. | About 3 days |
| **B. Option A, plus actually verifying the account** | We send ₹1 to the account. The bank tells us the real account holder's name. We check that name against the customer's PAN. If it does not match, we do not pay out. | About 3 weeks, plus a service provider |

## Our suggestion

**Option B eventually**, because sending ₹1 to check the name is the standard method in India and
it stops the problem completely rather than just blocking one route to it.

**Option A can be done at any time.** It needs no outside service and no banking relationship.

## Your answer — when you are ready

**Option: ________**

---

# Question 13 — For the government identity registry, do we build it or buy it?

## Issue

**We are legally required to send every customer's identity records to a central government
registry within 10 days of starting the relationship. We do not do this at all today. Do we build
the connection ourselves, or use a service provider?**

## Why it matters

This is not optional and there is no way to delegate it to a partner — the requirement is tied to
our own registration as a lender. **The 10-day clock is already running on every loan we have paid
out.**

There is also a benefit. The same registry lets us look a customer up before we collect their
documents. If they are already registered, we can pull their verified details instead of
collecting everything again. That is faster for the customer and it gives us a more reliable way
to identify people than we have today.

## Your options

| Option | What it involves | Notes |
|---|---|---|
| **A. Build it ourselves** | We connect directly to the government registry. Files are exchanged on a schedule. | More control, no ongoing fee, more work and more maintenance. |
| **B. Use a service provider** | A company handles the connection for us. | Faster to launch, ongoing cost per record. Several established providers exist. |

## Our suggestion

We need to know your appetite for an ongoing per-record cost against a longer build. **Option B is
usually faster to get compliant**, which matters because the clock is already running.

## Your answer

**Option: ________**

---

# Two things already decided — recorded here for completeness

**Product changes.** You confirmed that a product administrator can make any change to any
product, and that any major change must raise an information notice. We are building that notice.

**Creating applications.** You confirmed that only a full system administrator should be able to
create a loan application on behalf of a partner. We are restricting it.

---

# What happens after you answer

| You answer | We can then start |
|---|---|
| Questions 1 and 2 | The whole charges and fees system, the customer's official loan disclosure document, and the true cost-of-borrowing figure we are legally required to publish |
| Questions 3 and 4 | Early loan closure |
| Questions 5, 6 and 7 | Flexible payment handling |
| Question 8 | Correct repayment schedules |
| Question 9 | Payout safety limits |
| Question 10 | We can size the accounting work properly |
| Question 13 | The government registry connection |

**Everything else is already approved and under way.** 77 separate pieces of engineering work do
not need anything from you.

If any question here is unclear, or if the options do not match how you want the business to work,
say so. We would rather rewrite the question than get the wrong answer.
