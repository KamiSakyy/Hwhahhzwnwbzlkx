export interface TokenDef {
  address: string;
  symbol: string;
  name: string;
  decimals?: number;
}

export interface ChainDef {
  chainId: number;
  name: string;
  short: string;
  symbol: string;
  testnet?: boolean;
  rpcs: string[];
  explorer?: string; // tx/address explorer base
  blockscout?: string; // blockscout api v2 base (CORS-enabled)
  tokens: TokenDef[];
  faucets?: { name: string; url: string }[];
  hue: number; // avatar/hue
}

const T = (address: string, symbol: string, name: string, decimals = 18): TokenDef => ({
  address,
  symbol,
  name,
  decimals,
});

export const CHAINS: ChainDef[] = [
  {
    chainId: 1,
    name: "Ethereum",
    short: "ETH",
    symbol: "ETH",
    hue: 219,
    rpcs: [
      "https://ethereum-rpc.publicnode.com",
      "https://cloudflare-eth.com",
      "https://eth.drpc.org",
      "https://1rpc.io/eth",
    ],
    explorer: "https://etherscan.io",
    blockscout: "https://eth.blockscout.com",
    tokens: [
      T("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", "USD Coin"),
      T("0xdAC17F958D2ee523a2206206994597C13D831ec7", "USDT", "Tether USD"),
      T("0x6B175474E89094C44Da98b954EedeAC495271d0F", "DAI", "Dai Stablecoin"),
      T("0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599", "WBTC", "Wrapped BTC"),
      T("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "WETH", "Wrapped Ether"),
    ],
  },
  {
    chainId: 11155111,
    name: "Sepolia",
    short: "Sepolia",
    symbol: "ETH",
    testnet: true,
    hue: 174,
    rpcs: [
      "https://ethereum-sepolia-rpc.publicnode.com",
      "https://rpc.sepolia.org",
      "https://sepolia.drpc.org",
      "https://1rpc.io/sepolia",
    ],
    explorer: "https://sepolia.etherscan.io",
    blockscout: "https://sepolia.blockscout.com",
    tokens: [
      T("0x1c7D4B196Cb0C7B01d743Fbc6116a902378C8050", "USDC", "USDC (Sepolia)"),
      T("0x94A939CC07018Adb559F98E67E4b0558E4Bc1C23", "USDT", "USDT (Sepolia)"),
    ],
    faucets: [
      { name: "Alchemy faucet", url: "https://www.alchemy.com/faucets/ethereum-sepolia" },
      { name: "Chainlist faucets", url: "https://faucet.chainlist.org/sepolia" },
      { name: "ETHerscan faucet", url: "https://sepolia.etherscan.io/faucet" },
    ],
  },
  {
    chainId: 17000,
    name: "Holesky",
    short: "Holesky",
    symbol: "ETH",
    testnet: true,
    hue: 340,
    rpcs: [
      "https://ethereum-holesky-rpc.publicnode.com",
      "https://holesky.drpc.org",
    ],
    explorer: "https://holesky.etherscan.io",
    blockscout: "https://holesky.blockscout.com",
    tokens: [],
    faucets: [{ name: "Chainlist faucets", url: "https://faucet.chainlist.org/holesky" }],
  },
  {
    chainId: 56,
    name: "BNB Smart Chain",
    short: "BSC",
    symbol: "BNB",
    hue: 48,
    rpcs: [
      "https://bsc-rpc.publicnode.com",
      "https://bsc.drpc.org",
      "https://1rpc.io/bnb",
    ],
    explorer: "https://bscscan.com",
    blockscout: "https://bsc.blockscout.com",
    tokens: [
      T("0x55d398326f99059fF775485246999027B3197955", "USDT", "Tether USD (BSC)"),
      T("0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", "USDC", "USD Coin (BSC)"),
      T("0x0E09FaBB73Bd3Ade0a17ECC321fD13a19e81cE82", "CAKE", "PancakeSwap"),
    ],
  },
  {
    chainId: 97,
    name: "BSC Testnet",
    short: "BSCt",
    symbol: "tBNB",
    testnet: true,
    hue: 48,
    rpcs: ["https://bsc-testnet-rpc.publicnode.com", "https://bsc-testnet.drpc.org"],
    explorer: "https://testnet.bscscan.com",
    tokens: [],
    faucets: [{ name: "BSC testnet faucet", url: "https://faucet.binance.org/" }],
  },
  {
    chainId: 137,
    name: "Polygon",
    short: "Polygon",
    symbol: "POL",
    hue: 262,
    rpcs: [
      "https://polygon-bor-rpc.publicnode.com",
      "https://polygon.drpc.org",
      "https://1rpc.io/matic",
    ],
    explorer: "https://polygonscan.com",
    blockscout: "https://polygon.blockscout.com",
    tokens: [
      T("0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", "USDC", "USD Coin (PoS)"),
      T("0xc2132D05D31c914a87C6611C10748AEb04B58e8F", "USDT", "Tether USD (PoS)"),
      T("0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619", "WETH", "Wrapped ETH (PoS)"),
      T("0x8f3Cf7ad23Cd3CaDbD9735AFf958023239c6A063", "DAI", "Dai (PoS)"),
    ],
  },
  {
    chainId: 80002,
    name: "Polygon Amoy",
    short: "Amoy",
    symbol: "POL",
    testnet: true,
    hue: 262,
    rpcs: ["https://rpc-amoy.polygon.technology", "https://polygon-amoy-rpc.publicnode.com"],
    explorer: "https://amoy.polygonscan.com",
    blockscout: "https://amoy-polygon.blockscout.com",
    tokens: [],
    faucets: [{ name: "Polygon Amoy faucet", url: "https://faucet.circle.com/" }],
  },
  {
    chainId: 42161,
    name: "Arbitrum One",
    short: "Arbitrum",
    symbol: "ETH",
    hue: 226,
    rpcs: [
      "https://arb1.arbitrum.io/rpc",
      "https://arbitrum-one-rpc.publicnode.com",
      "https://arbitrum.drpc.org",
    ],
    explorer: "https://arbiscan.io",
    blockscout: "https://arbitrum.blockscout.com",
    tokens: [
      T("0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "USDC", "USD Coin (Arb)"),
      T("0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", "USDT", "Tether USD (Arb)"),
      T("0x912CE59144191C1204E64559FE8253a0e49E6548", "ARB", "Arbitrum"),
    ],
  },
  {
    chainId: 421614,
    name: "Arbitrum Sepolia",
    short: "ArbSep",
    symbol: "ETH",
    testnet: true,
    hue: 226,
    rpcs: [
      "https://sepolia-rollup.arbitrum.io/rpc",
      "https://arbitrum-sepolia-rpc.publicnode.com",
    ],
    explorer: "https://sepolia.arbiscan.io",
    blockscout: "https://arbitrum-sepolia.blockscout.com",
    tokens: [
      T("0x75739EB9f3aE418a78D3e64d9E6c38C4042604B7", "USDC", "USD Coin (ArbSep)"),
    ],
    faucets: [{ name: "Arbitrum Sepolia faucet", url: "https://arb1.arbitrum.io/faucet/sepolia" }],
  },
  {
    chainId: 10,
    name: "OP Mainnet",
    short: "Optimism",
    symbol: "ETH",
    hue: 4,
    rpcs: [
      "https://mainnet.optimism.io",
      "https://optimism-rpc.publicnode.com",
      "https://optimism.drpc.org",
    ],
    explorer: "https://optimistic.etherscan.io",
    blockscout: "https://optimism.blockscout.com",
    tokens: [
      T("0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85", "USDC", "USD Coin (OP)"),
      T("0x69f4D1788e39c87893C980c06EdF407CF5b51E21", "USDT", "Tether USD (OP)"),
    ],
  },
  {
    chainId: 11155420,
    name: "OP Sepolia",
    short: "OPSep",
    symbol: "ETH",
    testnet: true,
    hue: 4,
    rpcs: ["https://sepolia.optimism.io", "https://optimism-sepolia-rpc.publicnode.com"],
    explorer: "https://sepolia-optimism.etherscan.io",
    blockscout: "https://optimism-sepolia.blockscout.com",
    tokens: [
      T("0x5fd84251d923838227f6dc05b4918568355a37b1", "USDC", "USD Coin (OPSep)"),
    ],
    faucets: [{ name: "OP Sepolia faucet", url: "https://faucet.circle.com/" }],
  },
  {
    chainId: 8453,
    name: "Base",
    short: "Base",
    symbol: "ETH",
    hue: 214,
    rpcs: [
      "https://mainnet.base.org",
      "https://base-rpc.publicnode.com",
      "https://base.drpc.org",
    ],
    explorer: "https://basescan.org",
    blockscout: "https://base.blockscout.com",
    tokens: [
      T("0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", "USD Coin (Base)"),
      T("0xcbB7C0000aB88B473b1f5aFd9ef808440eed33Bf", "cbBTC", "Coinbase Wrapped BTC"),
    ],
  },
  {
    chainId: 84532,
    name: "Base Sepolia",
    short: "BaseSep",
    symbol: "ETH",
    testnet: true,
    hue: 214,
    rpcs: ["https://sepolia.base.org", "https://base-sepolia-rpc.publicnode.com"],
    explorer: "https://sepolia.basescan.org",
    blockscout: "https://base-sepolia.blockscout.com",
    tokens: [
      T("0x036CbD53842c5426634e7929541eC2318f3dCF7e", "USDC", "USD Coin (BaseSep)"),
    ],
    faucets: [{ name: "Base Sepolia faucet", url: "https://portal.cdp.coinbase.com/products/faucet" }],
  },
  {
    chainId: 43114,
    name: "Avalanche",
    short: "AVAX",
    symbol: "AVAX",
    hue: 348,
    rpcs: ["https://api.avax.network/ext/bc/C/rpc", "https://avalanche-c-rpc.publicnode.com"],
    explorer: "https://snowtrace.io",
    blockscout: "https://avax.blockscout.com",
    tokens: [
      T("0xB31f66AA3C1e785363F0875A1B74E27b85FD66c7", "USDC", "USD Coin (AVAX)"),
      T("0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7", "USDT", "Tether USD (AVAX)"),
    ],
  },
  {
    chainId: 100,
    name: "Gnosis",
    short: "Gnosis",
    symbol: "xDAI",
    hue: 240,
    rpcs: ["https://rpc.gnosischain.com", "https://gnosis-rpc.publicnode.com"],
    explorer: "https://gnosisscan.io",
    blockscout: "https://gnosis.blockscout.com",
    tokens: [
      T("0xDDAfbb505ad214D7b80b1f830fcCc89B60fb7A83", "USDC", "USD Coin (Gnosis)"),
    ],
  },
];

export function chainById(list: ChainDef[], id: number): ChainDef {
  return list.find((c) => c.chainId === id) ?? list[0];
}

const LS_CUSTOM = "obsidian.customChains.v1";

export function loadCustomChains(): ChainDef[] {
  try {
    if (typeof window === "undefined") return [];
    const raw = localStorage.getItem(LS_CUSTOM);
    if (!raw) return [];
    const arr = JSON.parse(raw) as ChainDef[];
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

export function saveCustomChains(list: ChainDef[]) {
  try {
    localStorage.setItem(LS_CUSTOM, JSON.stringify(list));
  } catch {
    /* noop */
  }
}

export function allChains(): ChainDef[] {
  const custom = loadCustomChains().filter((c) => !CHAINS.some((b) => b.chainId === c.chainId));
  return [...CHAINS, ...custom];
}

export function isAddress(s: string): boolean {
  return /^0x[0-9a-fA-F]{40}$/.test(s.trim());
}

export function shortAddr(a: string, n = 4): string {
  if (!a) return "";
  return `${a.slice(0, n + 2)}…${a.slice(-4)}`;
}
