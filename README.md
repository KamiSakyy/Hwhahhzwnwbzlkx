# ◆ Nova Wallet — полноценный мультичейн криптокошелёк (Android, Java + XML)

MetaMask/Trust Wallet уровня: EVM (15 сетей: Sepolia, Ethereum, Base, Arbitrum, Optimism, Polygon, BNB, Avalanche, zkSync, Linea, Scroll, Mantle, Fantom, Gnosis, Goerli) + Bitcoin (SegWit BIP84) + Solana. Web3j + BouncyCastle, Material 3, чёрная неоновая тема.

## Возможности
- Создание (BIP39 12 слов) / импорт (фраза 12/24 или приватный ключ), PIN + биометрия, ключи зашифрованы (Android Keystore AES-GCM)
- Балансы в крипте и USD (CoinGecko), отправка/получение ETH, ERC-20, BTC, SOL; QR: генерация + сканер
- Токены: известные USDT/USDC/DAI/WBTC/WETH + добавление любого ERC-20 по контракту, NFT ERC-721
- 🪙 **Создание своего ERC-20 токена в Sepolia Testnet — бесплатно** (контракт `contracts/MyToken.sol`): имя, тикер, эмиссия; адрес контракта для друзей (добавляется в MetaMask/Trust/Nova), кнопка «Поделиться»
- DeFi браузер с инжектом `window.ethereum` (Uniswap и др.), Swap, покупка (MoonPay), история в эксплорерах, краны Sepolia

## Сборка
GitHub Actions: `.github/workflows/build-apk.yml` → артефакт **NovaWallet-APK** (release, ≤50 МБ). Локально: `gradle assembleRelease`

⚠️ Sepolia — тестовая сеть: монеты не имеют ценности. Для реальных денег переключайте сеть на Ethereum/др. и тщательно храните фразу.
