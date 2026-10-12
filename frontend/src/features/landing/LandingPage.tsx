import { useEffect, useRef, useState, type CSSProperties, type MouseEvent, type ReactNode, type RefObject } from 'react'
import { paths } from '../../lib/router.ts'
import { LandingAccount } from './LandingAccount.tsx'
import { assemblySteps, landingDetails, readLandingTheme, saveLandingTheme, virtualBuildSlots, type LandingTheme } from './landingContent.ts'
import { PcAssemblyScroll } from './PcAssemblyScroll.tsx'
import './landing.css'

const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

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

export function LandingPage() {
  const scrollRoot = useRef<HTMLDivElement>(null)
  const heroArt = useRef<HTMLDivElement>(null)
  const [theme, setTheme] = useState<LandingTheme>(readLandingTheme)

  function toggleTheme() {
    const next = theme === 'dark' ? 'light' : 'dark'
    setTheme(next)
    saveLandingTheme(next)
  }

  // 배경 PC 장면이 마우스 반대 방향으로 조금 움직인다.
  function moveArt(event: MouseEvent<HTMLElement>) {
    const art = heroArt.current
    if (!art || reducedMotion()) return
    const rectangle = event.currentTarget.getBoundingClientRect()
    const x = (event.clientX - rectangle.left) / rectangle.width - 0.5
    const y = (event.clientY - rectangle.top) / rectangle.height - 0.5
    art.style.setProperty('--parallax-x', `${Math.round(-x * 24)}px`)
    art.style.setProperty('--parallax-y', `${Math.round(-y * 16)}px`)
  }

  function resetArt() {
    heroArt.current?.style.setProperty('--parallax-x', '0px')
    heroArt.current?.style.setProperty('--parallax-y', '0px')
  }

  function scrollToAssembly() {
    const root = scrollRoot.current
    const section = root?.querySelector<HTMLElement>('#assembly')
    if (!root || !section) return
    const top = section.getBoundingClientRect().top - root.getBoundingClientRect().top + root.scrollTop
    root.scrollTo({ top: Math.max(0, top - root.clientHeight * 0.6), behavior: reducedMotion() ? 'auto' : 'smooth' })
  }

  return (
    <div className="landing" data-theme={theme} ref={scrollRoot}>
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
          <a href={paths.home()} aria-current="page">
            <Icon><path d="M3 11l9-7 9 7" /><path d="M5 10v10h14V10" /></Icon>
            <span>홈</span>
            <span className="landing__nav-dot" aria-hidden="true" />
          </a>
          <a href={paths.new()}>
            <Icon><rect x="3" y="3" width="8" height="8" rx="1.5" /><rect x="13" y="3" width="8" height="8" rx="1.5" /><rect x="3" y="13" width="8" height="8" rx="1.5" /><path d="M17 13v8M13 17h8" /></Icon>
            <span>PC 구성하기</span>
          </a>
          <a href={paths.list()}>
            <Icon><rect x="6" y="2.5" width="12" height="19" rx="2" /><circle cx="12" cy="9" r="3" /><path d="M9.5 17h5" /></Icon>
            <span>내 PC</span>
          </a>
        </nav>
        <div className="landing__account">
          <LandingAccount />
        </div>
      </aside>

      <main className="landing__main">
        <section className="landing-hero" onMouseMove={moveArt} onMouseLeave={resetArt}>
          <div className="landing-hero__art" ref={heroArt} aria-hidden="true" style={{ '--parallax-x': '0px', '--parallax-y': '0px' } as CSSProperties}>
            <img src="/pc-assembly/poster-desktop.webp" alt="" fetchPriority="high" />
            <div className="landing-hero__glow" />
          </div>
          <div className="landing-hero__scrim" aria-hidden="true" />

          <button type="button" className="landing-hero__theme" aria-label={theme === 'dark' ? '라이트 모드로 전환' : '다크 모드로 전환'} onClick={toggleTheme}>
            {theme === 'dark'
              ? <Icon size={18}><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></Icon>
              : <Icon size={18}><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" /></Icon>}
          </button>

          <div className="landing-hero__copy">
            <p className="landing-eyebrow landing-hero__eyebrow landing-fade" aria-hidden="true">&gt; BUILD YOUR NEXT PC</p>
            <h1 className="landing-hero__title" aria-label="나만의 PC를, 더 선명하게.">
              <span className="landing-hero__line landing-hero__line--1" aria-hidden="true">나만의 PC를,</span>
              <span className="landing-hero__line landing-hero__line--2" aria-hidden="true">더 선명하게.<span className="landing-hero__caret" /></span>
            </h1>
            <p className="landing-hero__lead landing-fade">부품 하나부터 다음 업그레이드까지.<br />호환성과 현재 상품가를 확인하고 로그인 없이 구성을 시작하세요.</p>
            <div className="landing-hero__actions landing-fade">
              <a href={paths.new()} className="landing-cta">PC 빌드 시작 →</a>
            </div>
          </div>

          <button type="button" className="landing-hero__scroll landing-fade" onClick={scrollToAssembly}>
            <span className="landing-hero__scroll-label">SCROLL</span>
            <svg className="landing-hero__scroll-arrow" aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M12 4v14" /><path d="M6 12l6 6 6-6" /></svg>
            <span className="landing-hero__scroll-hint">내리면 조립됩니다</span>
          </button>
        </section>

        <section className="landing-section landing-section--intro">
          <div className="landing-heading">
            <p className="landing-eyebrow">ASSEMBLY</p>
            <h2>흩어진 부품이<br />한 대의 PC가 될 때까지.</h2>
            <p className="landing-heading__lead">스크롤을 내리는 만큼 조립이 진행됩니다. 순서는 실제로 PC를 조립할 때와 같습니다.</p>
          </div>
        </section>

        <PcAssemblyScroll
          scrollRoot={scrollRoot}
          renderStep={(index) => {
            const item = assemblySteps[index]
            return (
              <div className="assembly-step">
                <span className="assembly-step__label">{item.label}</span>
                <h3>{item.title}</h3>
                <p>{item.body}</p>
                <span className="assembly-step__meta">{item.meta}</span>
              </div>
            )
          }}
        />

        <section className="landing-section landing-section--alt">
          <Reveal scrollRoot={scrollRoot}>
            <div className="landing-build__head">
              <div className="landing-heading">
                <p className="landing-eyebrow">VIRTUAL BUILD</p>
                <h2>결제하기 전에,<br />화면에서 먼저 조립해 보세요.</h2>
                <p className="landing-heading__lead">부품을 슬롯에 하나씩 넣을 때마다 맞는지, 케이스에 들어가는지, 전부 합쳐 얼마인지가 바로 바뀝니다. 지금 쓰는 부품은 그대로 두고 바꿀 것만 넣어도 됩니다.</p>
              </div>
              <a href={paths.new()} className="landing-cta landing-cta--compact">가상 조립 해 보기 →</a>
            </div>

            <div className="landing-build">
              <div className="landing-build__bar">
                <div className="landing-build__title">
                  <span>내 구성</span>
                  <span className="landing-build__state">저장 안 됨 · 로그인하면 내 PC로 저장</span>
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
                  <div role="row" className={`landing-build__row${row.warn ? ' landing-build__row--warn' : ''}`} key={row.slot}>
                    <span role="cell" className="landing-build__slot">{row.slot}</span>
                    <span role="cell">{row.part}{row.kept && <span className="landing-build__kept">기존 부품</span>}</span>
                    <span role="cell" className="landing-build__note">
                      {row.warn
                        ? <span className="landing-build__warn" aria-label="확인 필요">!</span>
                        : <span className="landing-build__ok" aria-label="호환">✓</span>}
                      {row.note}
                    </span>
                    <span role="cell" className="landing-mono landing-build__price">{row.price}</span>
                  </div>
                ))}
              </div>
              <div className="landing-build__foot">
                <span>호환 확인 <strong>7</strong> · 확인 필요 <strong>1</strong></span>
                <span className="landing-build__total"><span>새로 살 부품 합계</span><span className="landing-mono">[합계]</span></span>
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

        <section className="landing-section landing-section--closing">
          <Reveal scrollRoot={scrollRoot} className="landing-closing">
            <p className="landing-mono landing-closing__prompt" aria-hidden="true">C:\pc-upgrade-lab&gt;<span className="landing-closing__cursor" /></p>
            <h2>지금 쓰는 PC부터<br />불러와 보세요.</h2>
            <p className="landing-heading__lead">로그인 없이 바로 시작합니다.</p>
            <div className="landing-closing__actions">
              <a href={paths.new()} className="landing-cta">PC 빌드 시작 →</a>
              <a href={paths.new()} className="landing-closing__link">내 PC 사양 먼저 불러오기</a>
            </div>
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
