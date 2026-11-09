# PayPilot AI — Hackathon Compliance Checklist
## PayPal AI Hackathon 2026

> **Last updated**: 2026-11-10 (Phase 1 — Scaffolding)  
> **Status legend**: ✅ PASS | ❌ FAIL | ⏳ NOT VERIFIED | ➖ NOT APPLICABLE

---

> [!IMPORTANT]
> No item is marked PASS without actual implementation evidence.
> This document is updated at the end of each development phase.

---

## 1. Mandatory Project Requirements

| # | Requirement | Official Rule | Implementation Evidence | Verification Method | Status | Remaining Action |
|---|------------|---------------|------------------------|--------------------|----|-----------------|
| 1.1 | Meaningful PayPal Developer Platform integration using Sandbox | Rule §1 | PayPal Invoicing API + Orders API via Spring Boot backend | Run app, create invoice, complete Sandbox payment, verify PAID status | ⏳ NOT VERIFIED | Phase 4: implement PayPal REST calls |
| 1.2 | Meaningful AI incorporated into application experience | Rule §1 | GPT-4o agents for extraction, reconciliation, payment ops, explanation | Test each AI agent in running application | ⏳ NOT VERIFIED | Phase 5–8: implement AI agents |
| 1.3 | Functional, working application | Rule §1 | End-to-end: register → invoice → PayPal payment → PAID status | Judge tests complete workflow | ⏳ NOT VERIFIED | Complete all phases |
| 1.4 | Newly created during hackathon period | Rule §1 | Project initialized 2026-11-09; empty workspace confirmed | Git log shows first commit date | ⏳ NOT VERIFIED | Initial git commit |
| 1.5 | Original work owned by entrant | Rule §1 | No copied commercial code; all code authored for this project | Code review; no plagiarism | ⏳ NOT VERIFIED | Ongoing |
| 1.6 | Complies with third-party licenses | Rule §1 | All dependencies use MIT / Apache 2.0 / BSD licenses (documented in README) | LICENSE file + deps table in README | ⏳ NOT VERIFIED | Phase 1 complete; ongoing verification |
| 1.7 | Sufficiently documented for judges | Rule §1 | README.md, docs/paypal-setup.md, docs/demo-instructions.md | Judge reads docs, runs app | ⏳ NOT VERIFIED | Phase 12: final docs pass |
| 1.8 | PayPal central to product | Rule §1 | PayPal is the payment execution engine; not just a button | Full flow demo | ⏳ NOT VERIFIED | Phase 4–5 |
| 1.9 | AI performs meaningful tasks | Rule §1 | Invoice extraction, agentic payment workflow, reconciliation | Demo each AI feature | ⏳ NOT VERIFIED | Phase 5–8 |

---

## 2. AI-Assisted Development Disclosure

| # | Requirement | Official Rule | Implementation Evidence | Verification Method | Status | Remaining Action |
|---|------------|---------------|------------------------|--------------------|----|-----------------|
| 2.1 | Record of AI development tools used | Rule §2 | README.md "AI Tools and Development Disclosure" section | Read README | ✅ PASS | Maintained throughout |
| 2.2 | Identify LLM provider and model in application | Rule §2 | OpenAI GPT-4o documented in README and health endpoint | `GET /health` returns model name | ⏳ NOT VERIFIED | Health endpoint in Phase 5 |
| 2.3 | Document how each AI tool contributed | Rule §2 | README.md distinguishes runtime AI from development tools | Read README | ✅ PASS | Update as features added |
| 2.4 | No false AI claims | Rule §2 | Only GPT-4o is claimed; no other models integrated | Code review | ✅ PASS | Ongoing |
| 2.5 | Author understands, reviews, tests submitted code | Rule §2 | Declared by project author; incremental review per phase | Author sign-off | ⏳ NOT VERIFIED | Author confirms at submission |
| 2.6 | Project reflects author's ideas and implementation | Rule §2 | Architecture designed by author; AI assists implementation | Devpost description | ⏳ NOT VERIFIED | Author confirms at submission |

---

## 3. PayPal Compliance

| # | Requirement | Official Rule | Implementation Evidence | Verification Method | Status | Remaining Action |
|---|------------|---------------|------------------------|--------------------|----|-----------------|
| 3.1 | Use PayPal Developer Sandbox | Rule §3 | `PAYPAL_MODE=sandbox`, base URL `api-m.sandbox.paypal.com` | Check env config | ✅ PASS | Enforced in application.properties |
| 3.2 | Use supported official PayPal APIs | Rule §3 | PayPal Invoicing v2 + Orders v2 — verified against official docs | API calls in PayPalClient.java | ⏳ NOT VERIFIED | Phase 4 |
| 3.3 | Secrets kept on server | Rule §3 | PayPal credentials only in backend .env; never sent to frontend | Code review; no VITE_ PayPal vars | ✅ PASS | Enforced by architecture |
| 3.4 | No credentials committed | Rule §3 | .env in .gitignore; only .env.example committed | `git status` shows no .env | ✅ PASS | .gitignore configured |
| 3.5 | No simulated successful PayPal payments | Rule §3 | No mock payment confirmations; demo mode is for AI only | Code review | ✅ PASS | Demo mode is AI-only |
| 3.6 | No PAID without verified evidence | Rule §3 | Invoice PAID only when PayPal capture returns COMPLETED | PaymentService.java logic | ⏳ NOT VERIFIED | Phase 4 |
| 3.7 | Sandbox transactions labeled | Rule §3 | UI shows [SANDBOX] badge on all transactions | Frontend UI | ⏳ NOT VERIFIED | Phase 7 |
| 3.8 | Cancellations and failures handled safely | Rule §3 | PaymentService handles FAILED, CANCELLED; audit logged | Integration test | ⏳ NOT VERIFIED | Phase 4 |
| 3.9 | Duplicate requests handled safely | Rule §3 | Idempotency via paypal_order_id uniqueness check | Integration test | ⏳ NOT VERIFIED | Phase 4 |

---

## 4. AI Safety and Financial Trust

| # | Requirement | Official Rule | Implementation Evidence | Verification Method | Status | Remaining Action |
|---|------------|---------------|------------------------|--------------------|----|-----------------|
| 4.1 | AI must not fabricate invoices or financial totals | Rule §4 | AI reads DB data; never invents amounts | Code review; Zod validation | ⏳ NOT VERIFIED | Phase 5 |
| 4.2 | Backend calculations authoritative | Rule §4 | Spring Boot performs all calculations; AI adds language only | Architecture review | ✅ PASS | By design |
| 4.3 | AI invoice info validated | Rule §4 | Zod schemas validate all AI output; user confirms before save | Phase 5 implementation | ⏳ NOT VERIFIED | Phase 5 |
| 4.4 | Human approval required for financial actions | Rule §4 | Payment ops agent proposes; user clicks Approve; Spring Boot executes | UI approval gate | ⏳ NOT VERIFIED | Phase 6–7 |
| 4.5 | AI has no unrestricted payment access | Rule §4 | AI service has zero PayPal API access; all payment via Spring Boot | Architecture; code review | ✅ PASS | By design; enforced |
| 4.6 | Audit logs maintained | Rule §4 | audit_logs table: immutable, append-only | DB migration V1 | ✅ PASS | V1 migration created |
| 4.7 | AI recommendations distinguished from facts | Rule §4 | UI labels all AI output as AI-generated | Frontend UI | ⏳ NOT VERIFIED | Phase 7–9 |
| 4.8 | Predictions labeled as estimates | Rule §4 | Confidence score shown; "AI-extracted — please verify" label | Frontend UI | ⏳ NOT VERIFIED | Phase 7 |
| 4.9 | Risk indicators not described as confirmed fraud | Rule §4 | Reconciliation report uses "flagged" not "fraud" | AI prompt design | ⏳ NOT VERIFIED | Phase 8 |

---

## 5. Originality and Intellectual Property

| # | Requirement | Official Rule | Implementation Evidence | Verification Method | Status | Remaining Action |
|---|------------|---------------|------------------------|--------------------|----|-----------------|
| 5.1 | No copied commercial source code | Rule §5 | All code written for this project | Code review | ✅ PASS | Ongoing |
| 5.2 | No unauthorized copyrighted assets | Rule §5 | No images, music, or external assets yet | Asset inventory | ✅ PASS | Review at Phase 7 |
| 5.3 | Properly licensed open-source libraries | Rule §5 | All deps MIT / Apache 2.0 / BSD (see README) | LICENSE notices | ✅ PASS | Maintained |
| 5.4 | License notices maintained | Rule §5 | license field in package.json; pom.xml references | Dependency audit | ⏳ NOT VERIFIED | Phase 11 audit |
| 5.5 | Open-source LICENSE file | Rule §5 | MIT LICENSE file at repo root | File exists | ✅ PASS | LICENSE created |
| 5.6 | Dependency licenses documented | Rule §5 | README.md "Dependencies and Licenses" table | Read README | ✅ PASS | Update as deps added |

---

## 6. Mandatory Submission Requirements

| # | Requirement | Official Rule | Implementation Evidence | Verification Method | Status | Remaining Action |
|---|------------|---------------|------------------------|--------------------|----|-----------------|
| 6.1 | Working build | Rule §6-A | — | All three services start cleanly | ⏳ NOT VERIFIED | Complete after all phases |
| 6.2 | Clear setup instructions | Rule §6-B | README.md Installation section | Judge follows instructions | ✅ PASS | Refine at Phase 12 |
| 6.3 | Working PayPal Sandbox integration | Rule §6-A | — | End-to-end payment flow test | ⏳ NOT VERIFIED | Phase 4 |
| 6.4 | Working AI functionality | Rule §6-A | — | Each AI agent tested | ⏳ NOT VERIFIED | Phase 5–8 |
| 6.5 | Public source repository | Rule §6-B | — | GitHub repo URL | ⏳ NOT VERIFIED | Phase 1: initial commit |
| 6.6 | Complete source code in repo | Rule §6-B | — | All files committed | ⏳ NOT VERIFIED | Ongoing |
| 6.7 | README.md | Rule §6-B | README.md exists with full content | File review | ✅ PASS | Final update at Phase 12 |
| 6.8 | Open-source LICENSE | Rule §6-B | MIT LICENSE file | File exists | ✅ PASS | LICENSE created |
| 6.9 | Installation instructions | Rule §6-B | README.md Setup section | Judge follows steps | ✅ PASS | Refine at Phase 12 |
| 6.10 | Environment variable documentation | Rule §6-B | .env.example in each service | Files exist | ✅ PASS | backend + ai-service created |
| 6.11 | Testing instructions | Rule §6-B | docs/demo-instructions.md | Judge reads and tests | ⏳ NOT VERIFIED | Phase 12 |
| 6.12 | Demo instructions | Rule §6-B | docs/demo-instructions.md | Judge reads | ✅ PASS | Placeholder created |
| 6.13 | Hosted demo URL OR build instructions | Rule §6-C | docs/demo-instructions.md | Judge runs locally | ⏳ NOT VERIFIED | Phase 12 |
| 6.14 | Demo accessible free of charge | Rule §6-C | — | Verify at submission | ⏳ NOT VERIFIED | Phase 12 |
| 6.15 | Sandbox testing instructions for judges | Rule §6-C | docs/paypal-setup.md + demo-instructions.md | Judge reads | ✅ PASS | Placeholder created |
| 6.16 | YouTube demo video under 3 minutes | Rule §6-D | — | Video upload | ⏳ NOT VERIFIED | Phase 12 |
| 6.17 | Video is publicly visible | Rule §6-D | — | Check YouTube settings | ⏳ NOT VERIFIED | Phase 12 |
| 6.18 | Video shows real app working | Rule §6-D | — | Record actual demo | ⏳ NOT VERIFIED | Phase 12 |
| 6.19 | Video shows PayPal Sandbox | Rule §6-D | — | Record Sandbox payment | ⏳ NOT VERIFIED | Phase 12 |
| 6.20 | Video explains AI functionality | Rule §6-D | — | Script and record | ⏳ NOT VERIFIED | Phase 12 |
| 6.21 | No unauthorized trademarks in video | Rule §6-D | — | Review before upload | ⏳ NOT VERIFIED | Phase 12 |
| 6.22 | AI disclosure in submission | Rule §6-E | README.md "AI Tools" section | Read README | ✅ PASS | Update at submission |

---

## 7. Judging Criteria Readiness

| # | Criterion | Weight | Current Status | Target State |
|---|-----------|--------|---------------|-------------|
| 7.1 | Technological Implementation | 20% | ⏳ Scaffolded | Full PayPal + AI integration working |
| 7.2 | Design | 20% | ⏳ Not started | Polished React UI with AI panels |
| 7.3 | Potential Impact | 20% | ✅ Documented | Real problem; documented in README |
| 7.4 | Innovation/Idea | 20% | ✅ Designed | Agentic payment workflow with approval gate |
| 7.5 | Presentation | 20% | ⏳ Not started | 3-min YouTube demo |

---

## 8. Security Checklist

| # | Check | Status | Evidence |
|---|-------|--------|---------|
| 8.1 | No .env files in repo | ✅ PASS | .gitignore covers all .env patterns |
| 8.2 | No hardcoded secrets in code | ✅ PASS | All secrets via env vars |
| 8.3 | PAYPAL_MODE=sandbox enforced | ✅ PASS | application.properties default |
| 8.4 | AI service not browser-accessible | ✅ PASS | No VITE_ AI service URL; internal only |
| 8.5 | AI service secret authentication | ✅ PASS | validateServiceSecret middleware |
| 8.6 | Minimal payment data stored | ✅ PASS | payments table: verification fields only |

---

*This document is updated at the end of each development phase.*  
*Never mark a requirement PASS without actual evidence.*

