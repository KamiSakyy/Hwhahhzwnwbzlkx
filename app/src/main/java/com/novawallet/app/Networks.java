package com.novawallet.app;
import java.util.*;
public class Networks{
 public static class Net{public String name,symbol,rpc,explorer; public long chainId; public boolean test; Net(String n,String s,long id,String r,String e,boolean t){name=n;symbol=s;chainId=id;rpc=r;explorer=e;test=t;}}
 public static final List<Net> ALL=Arrays.asList(
  new Net("Sepolia Testnet","SepoliaETH",11155111,"https://ethereum-sepolia-rpc.publicnode.com","https://sepolia.etherscan.io",true),
  new Net("Ethereum","ETH",1,"https://ethereum-rpc.publicnode.com","https://etherscan.io",false),
  new Net("Base","ETH",8453,"https://base-rpc.publicnode.com","https://basescan.org",false),
  new Net("Arbitrum One","ETH",42161,"https://arbitrum-one-rpc.publicnode.com","https://arbiscan.io",false),
  new Net("Optimism","ETH",10,"https://optimism-rpc.publicnode.com","https://optimistic.etherscan.io",false),
  new Net("Polygon","POL",137,"https://polygon-bor-rpc.publicnode.com","https://polygonscan.com",false),
  new Net("BNB Chain","BNB",56,"https://bsc-rpc.publicnode.com","https://bscscan.com",false),
  new Net("Avalanche","AVAX",43114,"https://avalanche-c-chain-rpc.publicnode.com","https://snowtrace.io",false),
  new Net("zkSync Era","ETH",324,"https://mainnet.era.zksync.io","https://explorer.zksync.io",false),
  new Net("Linea","ETH",59144,"https://linea-rpc.publicnode.com","https://lineascan.build",false),
  new Net("Scroll","ETH",534352,"https://scroll-rpc.publicnode.com","https://scrollscan.com",false),
  new Net("Mantle","MNT",5000,"https://rpc.mantle.xyz","https://mantlescan.xyz",false),
  new Net("Fantom","FTM",250,"https://fantom-rpc.publicnode.com","https://ftmscan.com",false),
  new Net("Gnosis","xDAI",100,"https://gnosis-rpc.publicnode.com","https://gnosisscan.io",false),
  new Net("Goerli Testnet","ETH",5,"https://ethereum-goerli-rpc.publicnode.com","https://goerli.etherscan.io",true)
 );
 public static Net current(android.content.Context c){ int i=Store.pub(c).getInt("net",0); return ALL.get(Math.min(i,ALL.size()-1)); }
 public static class Token{public String address,name,symbol; public int decimals; public long chainId; Token(String a,String n,String s,int d,long c){address=a;name=n;symbol=s;decimals=d;chainId=c;}}
 public static List<Token> tokens(android.content.Context c,long chain){ List<Token> out=new ArrayList<>(); // well-known per-chain defaults handled in UI; custom below
   String raw=Store.pub(c).getString("tokens",""); if(!raw.isEmpty())for(String p:raw.split(";;")){String[] f=p.split("\\|"); if(f.length==5&&Long.parseLong(f[4])==chain)out.add(new Token(f[0],f[1],f[2],Integer.parseInt(f[3]),chain));} return out; }
 public static void addToken(android.content.Context c,Token t){ String raw=Store.pub(c).getString("tokens",""); String e=t.address+"|"+t.name.replace("|"," ")+"|"+t.symbol+"|"+t.decimals+"|"+t.chainId; Store.pub(c).edit().putString("tokens",raw.isEmpty()?e:raw+";;"+e).apply(); }
 public static List<Token> known(long chain){ List<Token> l=new ArrayList<>(); if(chain==1||chain==11155111){} 
   if(chain==1){ l.add(new Token("0xdAC17F958D2ee523a2206206994597C13D831ec7","Tether USD","USDT",6,1)); l.add(new Token("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48","USD Coin","USDC",6,1)); l.add(new Token("0x6B175474E89094C44Da98b954EedeAC495271d0F","Dai","DAI",18,1)); l.add(new Token("0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599","Wrapped BTC","WBTC",8,1)); l.add(new Token("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2","Wrapped Ether","WETH",18,1)); }
   if(chain==137){ l.add(new Token("0xc2132D05D31c914a87C6611C10748AEb04B58e8F","Tether USD","USDT",6,137)); l.add(new Token("0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359","USD Coin","USDC",6,137)); }
   if(chain==56){ l.add(new Token("0x55d398326f99059fF775485246999027B3197955","Tether USD","USDT",18,56)); l.add(new Token("0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d","USD Coin","USDC",18,56)); }
   if(chain==8453){ l.add(new Token("0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913","USD Coin","USDC",6,8453)); }
   if(chain==42161){ l.add(new Token("0xaf88d065e77c8cC2239327C5EDb3A432268e5831","USD Coin","USDC",6,42161)); l.add(new Token("0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9","Tether USD","USDT",6,42161)); }
   return l; }
}
