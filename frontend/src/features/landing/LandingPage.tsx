import { useEffect, useRef, useState, type CSSProperties, type ReactNode, type RefObject } from 'react'
import { paths } from '../../lib/router.ts'
import { LandingAccount } from './LandingAccount.tsx'
import { compactLandingQuery, heroCopyAt, heroQuips, landingDetails, readLandingTheme, saveLandingTheme, virtualBuildSlots, type LandingTheme } from './landingContent.ts'
import { PcAssemblyScroll } from './PcAssemblyScroll.tsx'
import './landing.css'

const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches
const sampleChecked = virtualBuildSlots.filter((row) => row.status === 'checked').length
const sampleNeedsCheck = virtualBuildSlots.filter((row) => row.status === 'needs-check').length
const sampleOutsideScope = virtualBuildSlots.length - sampleChecked - sampleNeedsCheck

function useCompact() {
  const [compact, setCompact] = useState(() => window.matchMedia(compactLandingQuery).matches)
  useEffect(() => {
    const query = window.matchMedia(compactLandingQuery)
    const change = () => setCompact(query.matches)
    query.addEventListener('change', change)
    return () => query.removeEventListener('change', change)
  }, [])
  return compact
}

// 화면 80% 지점에 들어오면 한 번 나타난다. 모션 감소 설정이면 처음부터 보인다.
function Reveal({ scrollRoot, className, children }: { scrollRoot: RefObject<HTMLDivElement | null>; className?: string; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  const [shown, setShown] = useState(reducedMotion)

  useEffect(() => {
    const element = ref.current
    if (shown || !element) return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setShown(true)
        observer.disconnect()
      }
    }, { root: scrollRoot.current, rootMargin: '0px 0px -20% 0px' })
    observer.observe(element)
    return () => observer.disconnect()
  }, [scrollRoot, shown])

  return <div ref={ref} className={`landing-reveal${shown ? ' is-shown' : ''}${className ? ` ${className}` : ''}`}>{children}</div>
}

function Icon({ children, size = 20 }: { children: ReactNode; size?: number }) {
  return (
    <svg aria-hidden="true" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      {children}
    </svg>
  )
}

function HeroTitle({ headingHidden = false }: { headingHidden?: boolean }) {
  return (
    <>
      <h1 className="hero-copy__title" aria-hidden={headingHidden}>조립은 편하게,<br />정보는 확실하게</h1>
      <a href={paths.new()} className="landing-cta">PC 구성하기 <span aria-hidden="true">→</span></a>
    </>
  )
}

// 첫 화면 왼쪽 문구. 인트로 → 진행도 → 전원 켜는 중 → 캐치프레이즈 순서로 바뀐다.
function HeroCopy({ percent, onScrollHint }: { percent: number; onScrollHint: () => void }) {
  const copy = heroCopyAt(percent)
  const hint = Math.max(0, 1 - percent / 3)
  return (
    <div className="hero-copy">
      <div className="hero-copy__intro" aria-hidden={copy.intro === 0} style={{ '--reveal': copy.intro } as CSSProperties}>
        <p className="hero-copy__intro-label">PC 업그레이드 실험실</p>
        <h1 className="hero-copy__intro-title">내 PC, 어디부터 업그레이드할까?</h1>
        <p className="hero-copy__intro-lead">부품 호환성과 예상 비용을 한눈에 확인해 보세요.</p>
      </div>
      <div className="hero-copy__progress" aria-hidden="true" style={{ '--reveal': copy.progress, '--fill': copy.percent / 100 } as CSSProperties}>
        <p className="hero-copy__meter"><span>진행도</span><span className="hero-copy__dots">....</span><span>{copy.percent}%</span></p>
        <div className="hero-copy__meter-line" />
        <div className="hero-copy__quips">
          {heroQuips.map((quip, index) => (
            <p className={`hero-copy__quip${index === copy.quip ? ' is-active' : index < copy.quip ? ' is-before' : ''}`} key={quip}>{quip}</p>
          ))}
        </div>
      </div>
      <p className="hero-copy__power" aria-hidden="true" style={{ '--reveal': copy.power } as CSSProperties}>전원 켜는 중…</p>
      <div className={`hero-copy__final${copy.final > 0.5 ? ' is-shown' : ''}`} style={{ '--reveal': copy.final } as CSSProperties}>
        <HeroTitle headingHidden={copy.intro > 0} />
      </div>
      <button type="button" className={`hero-copy__scroll${hint > 0 ? '' : ' is-hidden'}`} style={{ '--reveal': hint } as CSSProperties} onClick={onScrollHint}>
        <span className="hero-copy__scroll-label">SCROLL</span>
        <svg className="hero-copy__scroll-arrow" aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M12 4v14" /><path d="M6 12l6 6 6-6" /></svg>
        <span className="hero-copy__scroll-hint">스크롤해서 조립 보기</span>
      </button>
    </div>
  )
}

// 왼쪽 탭 바. 랜딩과 PC 구성 화면이 같이 쓴다. current는 현재 화면의 탭이다.
export function LandingTabs({ current }: { current: 'home' | 'new' | 'list' | 'upgrade' }) {
  const page = (tab: typeof current) => (tab === current ? 'page' : undefined)
  const dot = (tab: typeof current) => tab === current && <span className="landing__nav-dot" aria-hidden="true" />
  return (
    <aside className="landing__tabs">
      <a href={paths.home()} className="landing__brand">
        <svg aria-hidden="true" width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          <rect x="5" y="2.5" width="14" height="19" rx="2" /><circle cx="12" cy="10" r="3.5" /><path d="M9 17.5h6" />
        </svg>
        <span className="landing__brand-text">
          <span className="landing__brand-name">PC 업그레이드 실험실</span>
          <span className="landing__brand-sub">UPGRADE LAB</span>
        </span>
      </a>
      <nav className="landing__nav" aria-label="주요 메뉴">
        <a href={paths.home()} aria-current={page('home')}>
          <Icon><path d="M3 11l9-7 9 7" /><path d="M5 10v10h14V10" /></Icon>
          <span>홈</span>
          {dot('home')}
        </a>
        <a href={paths.new()} aria-current={page('new')}>
          <Icon><rect x="3" y="3" width="8" height="8" rx="1.5" /><rect x="13" y="3" width="8" height="8" rx="1.5" /><rect x="3" y="13" width="8" height="8" rx="1.5" /><path d="M17 13v8M13 17h8" /></Icon>
          <span>PC 구성하기</span>
          {dot('new')}
        </a>
        <a href={paths.list()} aria-current={page('list')}>
          <Icon><rect x="6" y="2.5" width="12" height="19" rx="2" /><circle cx="12" cy="9" r="3" /><path d="M9.5 17h5" /></Icon>
          <span>내 PC</span>
          {dot('list')}
        </a>
        <a href={paths.upgrade()} aria-current={page('upgrade')}>
          <Icon><path d="M3 17l6-6 4 4 8-8" /><path d="M15 7h6v6" /></Icon>
          <span>업그레이드 플랜</span>
          {dot('upgrade')}
        </a>
      </nav>
      <div className="landing__account">
        <LandingAccount />
      </div>
    </aside>
  )
}

export function LandingPage() {
  const scrollRoot = useRef<HTMLDivElement>(null)
  const [theme, setTheme] = useState<LandingTheme>(readLandingTheme)
  const compact = useCompact()

  function toggleTheme() {
    const next = theme === 'dark' ? 'light' : 'dark'
    setTheme(next)
    saveLandingTheme(next)
  }

  function scrollHint() {
    const root = scrollRoot.current
    root?.scrollBy({ top: root.clientHeight * 0.8, behavior: reducedMotion() ? 'auto' : 'smooth' })
  }

  return (
    <div className="landing" data-theme={theme} ref={scrollRoot}>
      <LandingTabs current="home" />

      {/* 지금 테마를 보여 준다. 다크는 달, 화이트는 해. */}
      <button type="button" className="landing-theme" aria-label={theme === 'dark' ? '화이트 모드로 전환' : '다크 모드로 전환'} onClick={toggleTheme}>
        {theme === 'dark'
          ? <Icon size={18}><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" /></Icon>
          : <Icon size={18}><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></Icon>}
      </button>

      <main className="landing__main">
        {compact ? (
          <section className="hero-compact">
            <HeroTitle />
          </section>
        ) : (
          <PcAssemblyScroll scrollRoot={scrollRoot} renderCopy={(percent) => <HeroCopy percent={percent} onScrollHint={scrollHint} />} />
        )}

        <section className="landing-section landing-section--alt">
          <Reveal scrollRoot={scrollRoot}>
            <div className="landing-build__head">
              <div className="landing-heading">
                <p className="landing-eyebrow">VIRTUAL BUILD</p>
                <h2>결제하기 전에,<br />화면에서 먼저 조립해 보세요.</h2>
                <p className="landing-heading__lead">부품을 슬롯에 하나씩 넣을 때마다 맞는지, 케이스에 들어가는지, 전부 합쳐 얼마인지가 바로 바뀝니다. 지금 쓰는 부품은 그대로 두고 바꿀 것만 넣어도 됩니다.</p>
              </div>
            </div>

            <div className="landing-build">
              <div className="landing-build__bar">
                <div className="landing-build__title">
                  <span>예시 구성</span>
                  <span className="landing-build__state">부품별 호환성 · 가격 표시 예시</span>
                </div>
                <span className="landing-mono landing-build__count">8 SLOTS</span>
              </div>
              <div role="table" aria-label="가상 조립 예시">
                <div role="row" className="landing-build__row landing-build__row--head">
                  <span role="columnheader">슬롯</span>
                  <span role="columnheader">넣은 부품</span>
                  <span role="columnheader">호환성</span>
                  <span role="columnheader" className="landing-build__price">현재가</span>
                </div>
                {virtualBuildSlots.map((row) => (
                  <div role="row" className={`landing-build__row${row.status === 'needs-check' ? ' landing-build__row--warn' : ''}`} key={row.slot}>
                    <span role="cell" className="landing-build__slot">{row.slot}</span>
                    <span role="cell">{row.part}{row.kept && <span className="landing-build__kept">기존 부품</span>}</span>
                    <span role="cell" className="landing-build__note">
                      {row.status === 'needs-check' && <span className="landing-build__warn" aria-hidden="true">!</span>}
                      {row.status === 'checked' && <span className="landing-build__ok" aria-hidden="true">✓</span>}
                      {row.note}
                    </span>
                    <span role="cell" className="landing-mono landing-build__price">{row.kept ? '—' : '미확인'}</span>
                  </div>
                ))}
              </div>
              <div className="landing-build__foot">
                <span>규격 일치 <strong>{sampleChecked}</strong> · 확인 필요 <strong>{sampleNeedsCheck}</strong> · 별도 확인 <strong>{sampleOutsideScope}</strong></span>
                <span className="landing-build__total"><span>새로 살 부품 합계</span><span className="landing-mono">가격 미확인</span></span>
              </div>
            </div>
          </Reveal>
        </section>

        <section className="landing-section landing-section--details">
          <Reveal scrollRoot={scrollRoot}>
            <div className="landing-heading">
              <p className="landing-eyebrow">DETAILS</p>
              <h2>부품 고를 때 귀찮았던 것들,<br />대신 확인합니다.</h2>
            </div>
            <ol className="landing-details">
              {landingDetails.map((item) => (
                <li key={item.n}>
                  <span className="landing-mono landing-details__n">{item.n}</span>
                  <h3>{item.title}</h3>
                  <p>{item.body}</p>
                </li>
              ))}
            </ol>
          </Reveal>
        </section>

        <footer className="landing-footer">
          <span>© PC 업그레이드 실험실</span>
          <span>가격은 국내 판매처의 현재 상품가 기준이며, 구매 시점에 달라질 수 있습니다.</span>
        </footer>
      </main>
    </div>
  )
}
