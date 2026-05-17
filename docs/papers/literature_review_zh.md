# GemmaFit 文獻回顧

最後更新：2026-05-05

## Executive Summary

就目前最強的來源來看，GemmaFit 最穩健的定位不是「AI 教練會看出受傷或病症」，而是「在單鏡頭、裝置端條件下，針對可觀測的動作品質指標提供有邊界的訓練回饋」。這個定位同時受到姿態估計文獻、拒答式 AI、信心校準、motor learning 回饋研究，以及國家標準暨技術研究院（NIST）、美國食品藥物管理局（FDA）、世界衛生組織（WHO）、歐盟執委會與全球資訊網協會（W3C）的框架支持。文獻一致支持三件事：第一，AI 輸出要能追溯到資料、模型、條件與限制；第二，在不確定或不可觀測時，系統應該拒答或縮小判斷範圍；第三，回饋內容若要幫助學習，必須與任務、時機與頻率匹配，而不是持續、全面、過度自信地評論。

對 GemmaFit 最重要的正面結論是：以單鏡頭 pose landmarks 為基礎，去做 squat 深度、節奏、軀幹角度、對稱性提示，並在檢測到視角不充分、遮擋、信心不足或超出知識邊界時明確拒答，這在學術與標準上是有根基的產品方向。尤其 NIST AI RMF 明確要求系統在超出 knowledge limits 時能 fail safely，selective prediction 文獻也顯示以 coverage 換風險下降是合理策略。

負面結論也同樣清楚：現有證據不足以支持 GemmaFit 宣稱自己能以單相機做 injury diagnosis、clinical biomechanics、muscle activation estimation，或證明長期能降低受傷風險。Google 的 BlazePose 論文本身強調的是單眼 RGB、裝置端 landmarks 與 fitness tracking 可用性，不是醫療診斷驗證；校準文獻則反覆提醒，模型分數本身常常不可信，尤其一旦遇到資料分布轉移。

因此，GemmaFit 最可信的產品敘事是：以 evidence-linked、bounded、abstention-aware 的方式，提供非診斷性 movement-quality coaching。最不該做的，是把「動作品質提示」說成「醫療判斷」，把「pose-only muscle focus」說成「肌肉活化」，或把「從側面影片看到的深度」延伸成「對所有平面與所有風險因子都能判斷」。

## Evidence provenance in AI systems

### 核心文獻與標準

- PROV family overview：把 provenance 定義為關於 entities、activities、people 的資訊，用來評估資料的品質、可靠性與可信度（Groth & Moreau, 2013）。
- Model Cards：主張模型發布時要說明 intended use、評估程序、適用脈絡與 subgroup 表現（Mitchell et al., 2019）。
- Datasheets for Datasets：主張資料集應文檔化動機、組成、收集過程與推薦用途，以提升透明度與責任（Gebru et al., 2021）。
- NIST AI RMF 1.0：把 validation 定義為以 objective evidence 確認特定 intended use 的要求已滿足，並要求一般化限制被記錄（NIST, 2023）。
- NIST AI 600-1：把 content provenance、pre-deployment testing、incident disclosure 視為生成式 AI 風險治理核心，並要求檢視來源與引用、記錄限制與 provenance（Autio et al., 2024）。

### 關鍵發現與對 GemmaFit 的意義

這一串文獻的共同點是：可信 AI 並不只靠一個準確率數字，而是靠「輸出能否追溯回它憑什麼這樣說」。對 GemmaFit 而言，最直接的設計含義不是去做一般性「解釋型 AI」敘事，而是建立每次 coaching output 的證據鏈：哪個 view、哪些 landmarks、哪個 metric formula、哪個 threshold、在什麼 confidence/coverage 條件下產生，以及若拒答，拒答的原因是什麼。這種 evidence ledger 與 PROV、model card、datasheet 的精神相符。

文獻支持的安全邊界：GemmaFit 可以合理主張自己有「evidence provenance」：輸出不是自由生成，而是由可追溯的姿態特徵、規則與 bounded function calls 組成。這支持「可稽核 coaching」的說法，但不等於自動證明這些指標在所有人、所有角度、所有環境都正確。

原型啟發式：用「證據卡」呈現一則回饋，例如「深度判定依據：hip-knee 關係 + 視角=側面 + 可見度達標」，屬於合理的 prototype heuristic，但目前文獻沒有直接驗證「這種 UI 一定改善使用者信任與安全」。

產品政策：若 GemmaFit 要長期站得住腳，建議把 provenance 做到 metric level，而不只是 session level。也就是不只說「用了 BlazePose」，而要說「這一則建議是依據哪一個可觀測指標產生」。

開放研究問題：目前很少文獻直接研究「consumer fitness app 的 per-output evidence provenance 是否降低過度信任或錯誤行動」。這是 GemmaFit 未來可以自己貢獻的實證點。

## Selective prediction and reject option in safety-critical AI

### 核心文獻與標準

- Selective Classification for Deep Neural Networks：以 coverage 換 risk，讓使用者事先設定可接受風險，模型在必要時拒答（Geifman & El-Yaniv, 2017）。
- SelectiveNet：把 reject option 內建到模型訓練，而不是只在事後閾值化 confidence（Geifman & El-Yaniv, 2019）。
- Ovadia et al.：資料分布偏移下，不確定性與校準都可能劣化，說明拒答機制不能只在 IID 驗證集上評估（Ovadia et al., 2019）。
- NIST AI RMF 1.0：要求系統在超出 knowledge limits 時能 fail safely，並在高風險情境中優先納入 human intervention（NIST, 2023）。

### 關鍵發現與對 GemmaFit 的意義

對 GemmaFit 最有價值的不是「永遠給答案」，而是「在能看的項目上判斷，在看不到的項目上拒答」。Selective prediction 的核心就是部分判斷：讓系統在高把握度區間內輸出，在低把握度區間 abstain。這剛好契合單鏡頭健身 coaching 的現實。你可以在側面視角判深度、節奏、軀幹前傾，但同一段影片裡不一定應該評論 frontal knee valgus。這個例子本身不是文獻直接驗證過的規則，而是 selective prediction 與單鏡頭 observability 結合後的合理產品化。

文獻支持的安全邊界：GemmaFit 可以強烈主張「partial judgment is safer than forced judgment」。也就是說，系統可針對某些明確可觀測指標作出評價，同時對其他指標表示「本視角不足，暫不判斷」。這是文獻支持的安全邏輯。

原型啟發式：以 visibility、view classifier、landmark stability、metric-specific confidence 組合成「是否判斷」分數，是合理 heuristic；但 coverage-risk 曲線必須在真實手機錄影分布上重新量測，不能只套用研究原則。

產品政策：建議把 refusal 設計成一級功能，而不是錯誤處理。產品應明確表達「可以評 depth/tempo，不評 frontal valgus」，而不是在 UI 上假裝全面懂。

開放研究問題：目前缺的不是 reject option 概念，而是「fitness metrics 的 risk-coverage benchmarking」。GemmaFit 若能公開不同動作、不同攝影角度、不同身材與穿著條件下的 coverage-risk 曲線，將很有價值。

## Confidence calibration for pose-estimation metrics

### 核心文獻與標準

- BlazePose GHUM Holistic：單眼 RGB、裝置端、3D landmarks、適合 fitness tracking 與即時推論（Grishchenko et al., 2022）。
- On Calibration of Modern Neural Networks：現代深度網路常常 poorly calibrated；temperature scaling 是簡單有效的後處理方法之一（Guo et al., 2017）。
- Accurate Uncertainties for Deep Learning Using Calibrated Regression：回歸模型的 credible intervals 也需要事後校準（Kuleshov et al., 2018）。
- Ovadia et al.：資料偏移會讓 calibration 惡化，僅靠靜態 validation 不夠（Ovadia et al., 2019）。

### 關鍵發現與對 GemmaFit 的意義

最需要校準的，不是 raw keypoint score 本身，而是最終 coaching metric 的正確率。GemmaFit 最後要說的是「深度足不足」「節奏穩不穩」「軀幹是否過度前傾」，不是單純「左膝座標 confidence=0.83」。因此校準單位應轉成 metric level：例如「在側面、遮擋低、攝距 2-3 m 的條件下，squat depth 判定的正確率曲線為何」。這是將 BlazePose 與 calibration literature 接起來的合理做法。

文獻支持的安全邊界：GemmaFit 可以主張自己「使用 uncertainty-aware coaching」，前提是 confidence claim 經過校準，且只在特定 view/context 下成立。不能把未校準的 landmark confidence 直接包裝成「高可信 biomechanical truth」。

原型啟發式：用 visibility、landmark jitter、frame dropout、view quality 去估計 metric confidence，是實用 heuristic；但目前針對 BlazePose-derived fitness metrics 的專門校準證據仍然偏薄。這一節最該直接說「evidence is thin」。

產品政策：所有高層產品文案應避免把「confidence」說成「clinical certainty」。如果要顯示信心，建議顯示成「本視角下可判程度」或「建議可靠度」，並與拒答閾值綁定。

開放研究問題：GemmaFit 可以貢獻的最核心研究，是建立 pose-derived coaching metrics 的 calibration protocol：依視角、距離、身形、衣著、光線與動作速度分層，量測 accuracy、ECE 類指標、coverage-risk 與 user-facing calibration UI 的效果。

## Augmented feedback and motor learning

### 核心文獻與標準

- Sigrist et al. 回顧：擴增式 visual、auditory、haptic 與 multimodal feedback 一般能幫助 motor learning，但成效高度依賴回饋設計（Sigrist et al., 2013）。
- Winstein（1991）是此領域的經典 feedback paper，Sigrist 回顧將其列為重要來源之一。
- Wulf & Shea（2002）指出從 simple skills 歸納出的原則，不一定可直接外推到 complex motor skill learning；Sigrist 回顧亦明確列出。
- Wulf et al.（2010）綜述 motor skill learning 的影響因素，也被 Sigrist 回顧列為關鍵文獻。

### 關鍵發現與對 GemmaFit 的意義

這一節最重要的訊息是：回饋本身有用，但不是越多越好。Sigrist 的回顧明確說 augmented feedback 通常能提升 motor learning，但不同 modality、timing、frequency 與 task complexity 的效果不同，設計得不好反而會造成認知負荷、依賴或錯誤注意。這對 GemmaFit 很關鍵，因為產品容易掉進「每一秒都講話、每一個關節都點評」的陷阱。

文獻支持的安全邊界：GemmaFit 可以宣稱自己提供「task-linked movement feedback」，也可以合理預期這類 augmented feedback 能幫助使用者在練習當下調整動作；但不能直接把這推成「已證明造成穩定的長期 technique transfer」或「降低 injury risk」，除非有自己的前瞻性研究。

原型啟發式：短句、低頻、與明確指標連動的提示，如「再慢一點下降」「膝蓋再穩一點」或「下一次更深」，很符合回顧文獻脈絡；連珠炮式、同時評論多個部位，則較可能超載。這是從 review 文獻導出的合理 heuristic，但仍需產品實驗驗證。

產品政策：若 GemmaFit 要做 AI 語音或文字 coaching，最好把回饋分成「即時安全界線提示」與「組後摘要」，而非全程密集糾錯。這樣更貼近 motor learning literature 對 feedback scheduling 的保守共識。

開放研究問題：直接針對 consumer、單鏡頭、LLM-generated feedback 的隨機對照或 retention study 仍然稀少。GemmaFit 非常適合做：即時回饋 vs 組後摘要、文字 vs 語音、低頻 vs 高頻，以及外部焦點措辭 vs 身體部位措辭的比較。

## On-device LLM function-calling safety

### 核心文獻與標準

- Toolformer：語言模型可以學會何時呼叫 API、傳什麼參數、如何整合工具回傳結果（Schick et al., 2023）。
- Greshake et al.：間接 prompt injection 可操控 LLM-integrated applications、影響功能與 API 呼叫，且有效防護仍不足（Greshake et al., 2023）。
- NIST AI 600-1：針對 GAI 強調 governance、content provenance、pre-deployment testing、incident disclosure，並要求用 empirically validated methods 來評估 capability claims、檢查引用與紀錄限制（Autio et al., 2024）。
- NIST AI RMF 1.0：要求 valid and reliable、safe、accountable and transparent，並在系統不能自我偵錯時納入 human intervention（NIST, 2023）。
- OWASP GenAI Top 10：把 prompt injection、improper output handling、excessive agency 列為主要風險（OWASP, 2025）。

### 關鍵發現與對 GemmaFit 的意義

Toolformer 類研究證明「LLM 選工具」是可行的；但 Greshake 等人同樣清楚展示，只要系統把自然語言當控制介面，prompt injection 就能改寫功能與 API 呼叫。因此，GemmaFit 的真正安全不應建立在「Gemma 很聰明很守規矩」，而應建立在能力空間被硬性界定：只允許有限函式、固定參數型別、上下界檢查、view precondition、不可自由執行任意工具、以及後置規則驗證。這與 deterministic capability contract 一致。

文獻支持的安全邊界：GemmaFit 可以主張 bounded function calling 是一種合理的 safety architecture，尤其比自由文字直接驅動動作建議更可控；但當前文獻並沒有直接證明「裝置端小模型 + function calling」本身就足夠安全。安全主要來自 contract、schema、allowlist、驗證與 refusal，不是來自模型本身。

原型啟發式：把 LLM 角色縮成「在受限選項中選擇函式與提示模板」，而不是自由生成處方，屬於很有力的 heuristic。裝置端執行也可作為隱私最小化策略，但這是 architecture benefit，不是 prompt safety proof。

產品政策：GemmaFit 應把所有高風險決策外移到 deterministic layer：movement metric computation、thresholding、refusal policy、嚴格 schema validation，全都不應交給 LLM 自由裁量。LLM 最多只做 bounded selection 與 user-facing phrasing。

開放研究問題：目前幾乎沒有針對「on-device fitness coach with bounded tool use」的正式安全 benchmark。GemmaFit 可以建立自己的 red-team corpus：含對抗式文字、背景畫面文字、奇異姿勢、超出視角條件與工具 misuse 測試。

## AI ethics and regulatory framing for non-diagnostic health coaching

### 核心文獻與標準

- FDA General Wellness guidance：若軟體的 intended use 是維持或鼓勵健康生活方式，且與 diagnosis、cure、mitigation、prevention、treatment 無關，則不屬於 device 定義中的那一類軟體功能（FDA, 2026）。
- WHO AI for Health ethics guidance：AI for health 必須把倫理與人權放在設計、部署與使用核心，並提出六項共識原則與治理建議（WHO, 2021）。
- NIST AI RMF 1.0：把 trustworthy AI 定義為 valid and reliable、safe、secure and resilient、accountable and transparent、explainable and interpretable、privacy-enhanced、fair with harmful bias managed（NIST, 2023）。
- AI Act 官方框架：歐盟採 risk-based approach，多數 minimal/no risk AI 不受高強度義務，但高風險用途須滿足 documentation、logging、human oversight、accuracy、robustness 等要求（European Commission, 2026）。

### 關鍵發現與對 GemmaFit 的意義

對 GemmaFit 最直接的法規啟示，是 intended use 決定你站在哪條線。在美國，如果你把產品定位為一般健康與運動習慣促進，且不聲稱診斷、治療、預防疾病，FDA 的 general wellness 路徑是相對有利的。歐盟的 AI Act 則提醒：大多數低風險 AI 可以是 minimal/no risk，但一旦系統用途碰到 health/safety 的高風險類別，documentation、logging、human oversight 與 robustness 義務就會大幅提升。

文獻支持的安全邊界：GemmaFit 可以自稱 non-diagnostic health coaching / movement-quality coaching。這個 framing 與 FDA general wellness 及 WHO/NIST 的可信與人本原則相容。

原型啟發式：在 UI 與文案中加入「本系統提供動作品質建議，非醫療診斷；若疼痛、受傷或症狀持續，請尋求專業醫療協助」屬於必要但不足的 heuristic。真正關鍵仍是功能邊界與 claim 邊界一致。

產品政策：GemmaFit 應明確避免把 single-camera coaching 說成 injury diagnosis，避免把 pose-only muscle focus 說成 muscle activation，避免宣稱能預防或治療特定疾病或傷害。這不只是保守文案，而是可能影響法規定位的核心決策。

開放研究問題：不同司法轄區對 wellness、fitness、rehabilitation、medical purpose 的界線並不完全一致。GemmaFit 若跨境上架，仍需逐地 legal review；文獻與官方框架提供的是方向，不是取代法律意見。

## What GemmaFit can credibly claim, must avoid claiming, and could contribute

### GemmaFit 可以可信地主張什麼

第一，GemmaFit 可以主張自己使用單鏡頭姿態 landmarks 與裝置端 AI，提供非診斷性的動作品質 coaching，例如 squat 深度、節奏、動作一致性與部分姿勢線索的提示。第二，可以主張系統不是凡事必答，而是採取 partial judgment：在能觀測、已校準、視角合適時給回饋；在視角不足、遮擋或信心不足時拒答。第三，可以主張輸出具有 evidence provenance：每則建議都可追溯到可見姿態特徵、規則、所用模型與限制條件。第四，只要文案清楚，GemmaFit 也可以把「muscle focus」做成動作模式導向的估計提示，但必須明說那是 movement-pattern estimate，而不是生理量測。

### GemmaFit 必須避免主張什麼

GemmaFit 不應主張能以手機單鏡頭做 injury diagnosis、臨床級 biomechanical assessment、muscle activation estimation、rehabilitation efficacy，或對疼痛與疾病做診斷、治療、預防判斷。也不應宣稱「任何角度都能準確評估所有動作缺陷」，更不應暗示只靠 pose landmarks 就能判定某人是否有醫療風險。若沒有自己的前瞻性研究，也不要主張已證明能降低 injury risk 或帶來長期 motor retention。

### GemmaFit 可以貢獻哪些研究缺口

最值得做的三類研究是：一，metric-level calibration，不是只校準關鍵點，而是校準深度、節奏、軀幹角度等 coaching metrics；二，view-conditioned abstention，公開各動作在不同視角、遮擋與環境下的 coverage-risk 曲線；三，feedback effectiveness，比較即時回饋、組後摘要、低頻 vs 高頻回饋對 technique correction 與 retention 的影響。第四個值得開新坑的是 bounded on-device LLM safety benchmark：證明 deterministic contract、schema validation、refusal policy 與 post-hoc checks，能否實際降低 prompt injection 與 tool misuse。

### 總結判斷

若採最保守、最可信的學術立場，GemmaFit 的最佳句型不是「我知道你哪裡受傷」，而是「在這個視角與信心條件下，我能對你的動作品質某些面向給出可追溯、可拒答、非診斷性的建議」。這個主張有文獻後盾。超過這條線的地方，今天的證據仍然不夠。

## 整合參考文獻

Autio, C., Schwartz, R., Dunietz, J., Jain, S., Stanley, M., Tabassi, E., Hall, P., & Roberts, K. (2024). Artificial Intelligence Risk Management Framework: Generative Artificial Intelligence Profile (NIST AI 600-1).

European Commission. (2026). AI Act.

FDA. (2026). General Wellness: Policy for Low Risk Devices.

Gebru, T., Morgenstern, J., Vecchione, B., Wortman Vaughan, J., Wallach, H., Daume III, H., & Crawford, K. (2021). Datasheets for Datasets.

Geifman, Y., & El-Yaniv, R. (2017). Selective Classification for Deep Neural Networks.

Geifman, Y., & El-Yaniv, R. (2019). SelectiveNet: A Deep Neural Network with an Integrated Reject Option.

Greshake, K., Abdelnabi, S., Mishra, S., Endres, C., Holz, T., & Fritz, M. (2023). Not what you've signed up for: Compromising Real-World LLM-Integrated Applications with Indirect Prompt Injection.

Grishchenko, I., Bazarevsky, V., Zanfir, A., Bazavan, E. G., Zanfir, M., Yee, R., Raveendran, K., Zhdanovich, M., Grundmann, M., & Sminchisescu, C. (2022). BlazePose GHUM Holistic: Real-time 3D Human Landmarks and Pose Estimation.

Groth, P., & Moreau, L. (2013). PROV-Overview: An Overview of the PROV Family of Documents.

Guo, C., Pleiss, G., Sun, Y., & Weinberger, K. Q. (2017). On Calibration of Modern Neural Networks.

Kuleshov, V., Fenner, N., & Ermon, S. (2018). Accurate Uncertainties for Deep Learning Using Calibrated Regression.

Mitchell, M., Wu, S., Zaldivar, A., Barnes, P., Vasserman, L., Hutchinson, B., Spitzer, E., Raji, I. D., & Gebru, T. (2019). Model Cards for Model Reporting.

NIST. (2023). Artificial Intelligence Risk Management Framework (AI RMF 1.0).

OWASP. (2025). Top 10 Risks & Mitigations for LLMs and Gen AI Apps.

Ovadia, Y., Fertig, E., Ren, J., Nado, Z., Sculley, D., Nowozin, S., Dillon, J. V., Lakshminarayanan, B., & Snoek, J. (2019). Can You Trust Your Model's Uncertainty? Evaluating Predictive Uncertainty Under Dataset Shift.

Schick, T., Dwivedi-Yu, J., Dessi, R., Raileanu, R., Lomeli, M., Zettlemoyer, L., Cancedda, N., & Scialom, T. (2023). Toolformer: Language Models Can Teach Themselves to Use Tools.

Sigrist, R., Rauter, G., Riener, R., & Wolf, P. (2013). Augmented visual, auditory, haptic, and multimodal feedback in motor learning: A review.

Winstein, C. J. (1991). Knowledge of results and motor learning: implications for physical therapy.

WHO. (2021). Ethics and governance of artificial intelligence for health.

Wulf, G., & Shea, C. H. (2002). Principles derived from the study of simple skills do not generalize to complex skill learning.

Wulf, G., Shea, C., & Lewthwaite, R. (2010). Motor skill learning and performance: A review of influential factors.
